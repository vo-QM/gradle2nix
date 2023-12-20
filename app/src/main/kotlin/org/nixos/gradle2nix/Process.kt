package org.nixos.gradle2nix

import org.nixos.gradle2nix.metadata.Artifact as ArtifactMetadata
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okio.ByteString.Companion.decodeHex
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.source
import org.nixos.gradle2nix.model.Repository
import org.nixos.gradle2nix.model.ResolvedConfiguration
import org.nixos.gradle2nix.env.ArtifactFile
import org.nixos.gradle2nix.env.ArtifactSet
import org.nixos.gradle2nix.env.Env
import org.nixos.gradle2nix.env.Module
import org.nixos.gradle2nix.env.ModuleId
import org.nixos.gradle2nix.metadata.Checksum
import org.nixos.gradle2nix.metadata.Component
import org.nixos.gradle2nix.metadata.Md5
import org.nixos.gradle2nix.metadata.Sha1
import org.nixos.gradle2nix.metadata.Sha256
import org.nixos.gradle2nix.metadata.Sha512
import org.nixos.gradle2nix.metadata.VerificationMetadata
import org.nixos.gradle2nix.metadata.parseVerificationMetadata
import org.nixos.gradle2nix.model.DependencyCoordinates
import org.nixos.gradle2nix.model.Version
import org.nixos.gradle2nix.module.GradleModule
import org.nixos.gradle2nix.module.Variant

// Local Maven repository for testing
private val m2 = System.getProperty("org.nixos.gradle2nix.m2")

private fun shouldSkipRepository(repository: Repository): Boolean {
    return repository.artifactResources.all { it.startsWith("file:") && (m2 == null || !it.startsWith(m2)) } ||
            repository.metadataResources.all { it.startsWith("file:") && (m2 == null || !it.startsWith(m2)) }
}

fun processDependencies(config: Config): Env {
    val verificationMetadata = readVerificationMetadata(config)
    val verificationComponents = verificationMetadata?.components?.associateBy { it.id } ?: emptyMap()
    val moduleCache = mutableMapOf<DependencyCoordinates, GradleModule?>()
    val pomCache = mutableMapOf<DependencyCoordinates, Pair<String, ArtifactFile>?>()
    val ivyCache = mutableMapOf<DependencyCoordinates, Pair<String, ArtifactFile>?>()
    val configurations = readDependencyGraph(config)

    val repositories = configurations
        .flatMap { it.repositories }
        .associateBy { it.id }
        .filterNot { (id, repo) ->
            if (shouldSkipRepository(repo)) {
                config.logger.warn("$id: all URLs are files; skipping")
                true
            } else {
                false
            }
        }
    if (repositories.isEmpty()) {
        config.logger.warn("no repositories found in any configuration")
        return emptyMap()
    }
    config.logger.debug("Repositories:\n  ${repositories.values.joinToString("\n  ")}")

    return configurations.asSequence()
        .flatMap { it.allDependencies.asSequence() }
        .filterNot { it.repository == null || it.repository !in repositories }
        .groupBy { ModuleId(it.id.group, it.id.module) }
        .mapValues { (_, deps) ->
            val byVersion = deps.groupBy { it.id }
                .mapValues { (componentId, deps) ->
                    val dep = MergedDependency(
                        id = componentId,
                        repositories = deps.mapNotNull { repositories[it.repository] }.distinct()
                    )
                    val component = verificationComponents[componentId]
                        ?: verifyComponentFilesInCache(config, componentId)
                        ?: verifyComponentFilesInTestRepository(config, componentId)
                        ?: config.logger.error("$componentId: no dependency metadata found")

                    val gradleModule = moduleCache.getOrPut(componentId) {
                        maybeDownloadGradleModule(config.logger, component, dep.repositories)?.artifact?.second
                    }
                    val pomArtifact = pomCache.getOrPut(componentId) {
                        maybeDownloadMavenPom(config.logger, component, dep.repositories, gradleModule)
                    }
                    val ivyArtifact = ivyCache.getOrPut(componentId) {
                        maybeDownloadIvyDescriptor(config.logger, component, dep.repositories)
                    }

                    val files = buildMap {
                        if (pomArtifact != null) put(pomArtifact.first, pomArtifact.second)
                        if (ivyArtifact != null) put(ivyArtifact.first, ivyArtifact.second)
                        for (artifact in component.artifacts) {
                            put(
                                artifact.name,
                                ArtifactFile(
                                    urls = dep.repositories.flatMap { repo ->
                                        artifactUrls(config.logger, componentId, artifact.name, repo, gradleModule)
                                    }.distinct(),
                                    hash = artifact.checksums.first().toSri()
                                )
                            )
                        }
                    }.toSortedMap()

                    ArtifactSet(files)
                }
                .mapKeys { Version(it.key.version) }
                .toSortedMap(Version.Comparator.reversed())
            Module(byVersion)
        }
        .toSortedMap(compareBy(ModuleId::toString))
}

private fun readVerificationMetadata(config: Config): VerificationMetadata? {
    return parseVerificationMetadata(config.logger, config.projectDir.resolve("gradle/verification-metadata.xml"))
}

@OptIn(ExperimentalSerializationApi::class)
private fun readDependencyGraph(config: Config): List<ResolvedConfiguration> {
    return config.projectDir.resolve("build/reports/nix-dependency-graph/dependency-graph.json")
        .inputStream()
        .buffered()
        .use { input -> Json.decodeFromStream(input) }
}

private fun verifyComponentFilesInCache(
    config: Config,
    id: DependencyCoordinates,
): Component? {
    val cacheDir = with(id) { config.gradleHome.resolve("caches/modules-2/files-2.1/$group/$module/$version") }
    if (!cacheDir.exists()) {
        return null
    }
    val verifications = cacheDir.walk().filter { it.isFile }.map { f ->
        ArtifactMetadata(f.name, sha256 = Sha256(f.sha256()))
    }
    config.logger.log("$id: obtained artifact hashes from Gradle cache.")
    return Component(id, verifications.toList())
}

private fun verifyComponentFilesInTestRepository(
    config: Config,
    id: DependencyCoordinates
): Component? {
    if (m2 == null) return null
    val dir = with(id) {
        File(URI.create(m2)).resolve("${group.replace(".", "/")}/$module/$version")
    }
    if (!dir.exists()) {
        config.logger.log("$id: not found in m2 repository; tried $dir")
        return null
    }
    val verifications = dir.walk().filter { it.isFile && it.name.startsWith(id.module) }.map { f ->
        ArtifactMetadata(
            f.name,
            sha256 = Sha256(f.sha256())
        )
    }
    config.logger.log("$id: obtained artifact hashes from test Maven repository.")
    return Component(id, verifications.toList())
}

private fun maybeDownloadGradleModule(
    logger: Logger,
    component: Component,
    repos: List<Repository>
): ArtifactDownload<Pair<String, GradleModule>>? {
    if (component.artifacts.none { it.name.endsWith(".module") }) return null
    val filename = with(component.id) { "$module-$version.module" }
    return maybeDownloadArtifact(logger, component.id, filename, repos)?.let { artifact ->
        try {
            ArtifactDownload(
                filename to JsonFormat.decodeFromString<GradleModule>(artifact.artifact),
                artifact.url,
                artifact.hash
            )
        } catch (e: SerializationException) {
            logger.warn("${component.id}: failed to parse Gradle module metadata from ${artifact.url}")
            null
        }
    }
}

private fun maybeDownloadMavenPom(
    logger: Logger,
    component: Component,
    repos: List<Repository>,
    gradleModule: GradleModule?
): Pair<String, ArtifactFile>? {
    if (component.artifacts.any { it.name.endsWith(".pom") }) return null
    val pomRepos = repos.filter { "mavenPom" in it.metadataSources }
    if (pomRepos.isEmpty()) return null
    val filename = with(component.id) { "$module-$version.pom" }

    return maybeDownloadArtifact(logger, component.id, filename, pomRepos)?.let { artifact ->
        filename to ArtifactFile(
            urls = pomRepos.flatMap { repo ->
                artifactUrls(logger, component.id, filename, repo, gradleModule)
            }.distinct(),
            hash = artifact.hash.toSri()
        )
    }
}

private fun maybeDownloadIvyDescriptor(
    logger: Logger,
    component: Component,
    repos: List<Repository>,
): Pair<String, ArtifactFile>? {
    val ivyRepos = repos.filter { "ivyDescriptor" in it.metadataSources }
    if (ivyRepos.isEmpty()) return null

    val urls = ivyRepos
        .flatMap { repo ->
            val attributes = attributes(component.id, repo)
            repo.metadataResources.mapNotNull { fill(it, attributes).takeIf(::isUrlComplete) }
        }
        .filter { url ->
            component.artifacts.none { url.substringAfterLast('/') == it.name }
        }

    var artifact: ArtifactDownload<String>? = null

    for (url in urls) {
        try {
            val source = HashingSource.sha256(URL(url).openStream().source())
            val text = source.buffer().readUtf8()
            val hash = source.hash
            artifact = ArtifactDownload(text, url, Sha256(hash.hex()))
            break
        } catch (e: IOException) {
            // Pass
        }
    }

    if (artifact == null) {
        logger.debug("ivy descriptor not found in urls: $urls")
        return null
    }
    return artifact.artifact to ArtifactFile(
        urls = urls,
        hash = artifact.hash.toSri()
    )
}

private fun maybeDownloadArtifact(
    logger: Logger,
    id: DependencyCoordinates,
    filename: String,
    repos: List<Repository>
): ArtifactDownload<String>? {
    val urls = repos.flatMap { artifactUrls(logger, id, filename, it, null)}

    logger.debug("artifact $filename: $urls")

    for (url in urls) {
        try {
            val source = HashingSource.sha256(URL(url).openStream().source())
            val text = source.buffer().readUtf8()
            val hash = source.hash
            return ArtifactDownload(text, url, Sha256(hash.hex()))
        } catch (e: IOException) {
            // Pass
        }
    }

    logger.debug("artifact $filename not found in any repository")
    return null
}

private fun File.sha256(): String {
    val source = HashingSource.sha256(source())
    source.buffer().readAll(blackholeSink())
    return source.hash.hex()
}

private fun Checksum.toSri(): String {
    val hash = value.decodeHex().base64()
    return when (this) {
        is Md5 -> "md5-$hash"
        is Sha1 -> "sha1-$hash"
        is Sha256 -> "sha256-$hash"
        is Sha512 -> "sha512-$hash"
    }
}

private fun artifactUrls(
    logger: Logger,
    id: DependencyCoordinates,
    filename: String,
    repository: Repository,
    module: GradleModule?,
): List<String> {
    val groupAsPath = id.group.replace(".", "/")

    val repoFilename = module?.let { m ->
        m.variants
            .asSequence()
            .flatMap(Variant::files)
            .find { it.name == filename }
    }?.url ?: filename

    val attributes = mutableMapOf(
        "organisation" to if (repository.m2Compatible) groupAsPath else id.group,
        "module" to id.module,
        "revision" to id.version,
    ) + fileAttributes(repoFilename, id.version)

    val resources = when (attributes["ext"]) {
        "pom" -> if ("mavenPom" in repository.metadataSources) repository.metadataResources else repository.artifactResources
        "xml" -> if ("ivyDescriptor" in repository.metadataSources) repository.metadataResources else repository.artifactResources
        "module" -> if ("gradleMetadata" in repository.metadataSources || "ignoreGradleMetadataRedirection" !in repository.metadataSources) {
            repository.metadataResources
        } else {
            repository.artifactResources
        }
        else -> repository.artifactResources
    }.map { it.replaceFirst("-[revision]", "-${id.artifactVersion}") }

    val urls = mutableListOf<String>()

    for (resource in resources) {
        val location = attributes.entries.fold(fill(resource, attributes)) { acc, (key, value) ->
            acc.replace("[$key]", value)
        }
        if (location.none { it == '[' || it == ']' }) {
            urls.add(location)
        } else {
            logger.warn("failed to construct artifact URL: $location")
        }
    }

    return urls.distinct()
}

private val optionalRegex = Regex("\\(([^)]+)\\)")
private val attrRegex = Regex("\\[([^]]+)]")

private fun fill(template: String, attributes: Map<String, String>): String {
    return optionalRegex.replace(template) { match ->
        attrRegex.find(match.value)?.groupValues?.get(1)?.let { attr ->
            attributes[attr]?.takeIf { it.isNotBlank() }?.let { value ->
                match.groupValues[1].replace("[$attr]", value)
            }
        } ?: ""
    }
}

private fun isUrlComplete(url: String): Boolean = !url.contains("[")

private fun attributes(id: DependencyCoordinates, repository: Repository): Map<String, String> = buildMap {
    put("organisation", if (repository.m2Compatible) id.group.replace(".", "/") else id.group)
    put("module", id.module)
    put("revision", id.version)
}

// Gradle persists artifacts with the Maven artifact pattern, which may not match the repository's pattern.
private fun fileAttributes(file: String, version: String): Map<String, String> {
    val parts = Regex("(.+)-$version(-([^.]+))?(\\.(.+))?").matchEntire(file) ?: return emptyMap()

    val (artifact, _, classifier, _, ext) = parts.destructured

    return buildMap {
        put("artifact", artifact)
        put("classifier", classifier)
        put("ext", ext)
    }
}

private data class MergedDependency(
    val id: DependencyCoordinates,
    val repositories: List<Repository>
)

private data class ArtifactDownload<T>(
    val artifact: T,
    val url: String,
    val hash: Checksum
)
