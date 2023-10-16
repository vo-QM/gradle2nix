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
import org.nixos.gradle2nix.dependencygraph.model.Repository
import org.nixos.gradle2nix.dependencygraph.model.ResolvedConfiguration
import org.nixos.gradle2nix.dependencygraph.model.ResolvedDependency
import org.nixos.gradle2nix.env.ArtifactFile
import org.nixos.gradle2nix.env.ArtifactSet
import org.nixos.gradle2nix.env.Env
import org.nixos.gradle2nix.env.Module
import org.nixos.gradle2nix.env.ModuleId
import org.nixos.gradle2nix.env.ModuleVersionId
import org.nixos.gradle2nix.env.Version
import org.nixos.gradle2nix.metadata.Checksum
import org.nixos.gradle2nix.metadata.Component
import org.nixos.gradle2nix.metadata.Md5
import org.nixos.gradle2nix.metadata.Sha1
import org.nixos.gradle2nix.metadata.Sha256
import org.nixos.gradle2nix.metadata.Sha512
import org.nixos.gradle2nix.metadata.VerificationMetadata
import org.nixos.gradle2nix.metadata.parseVerificationMetadata
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
    val verificationComponents = verificationMetadata?.components?.associateBy {
        ModuleVersionId(ModuleId(it.group, it.name), it.version)
    } ?: emptyMap()
    val moduleCache = mutableMapOf<ModuleVersionId, GradleModule?>()
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
        return Env(emptyMap())
    }
    config.logger.debug("Repositories:\n  ${repositories.values.joinToString("\n  ")}")

    val modules = configurations.asSequence()
        .flatMap { it.allDependencies.asSequence() }
        .filterNot { it.id.startsWith("project ") || it.repository == null || it.repository !in repositories }
        .groupBy { ModuleId(it.coordinates.group, it.coordinates.module) }
        .mapValues { (id, deps) ->
            val versions = deps.groupBy { Version(it.coordinates.version) }
                .mapValues { (version, deps) ->
                    val componentId = ModuleVersionId(id, version)
                    val dep = MergedDependency(
                        id = componentId,
                        repositories = deps.mapNotNull { repositories[it.repository] }
                    )
                    val component = verificationComponents[componentId]
                        ?: verifyComponentFilesInCache(config, componentId)
                        ?: verifyComponentFilesInTestRepository(config, componentId)
                    val gradleModule = moduleCache.getOrPut(componentId) {
                        maybeGetGradleModule(config.logger, componentId, dep.repositories)
                    }
                    ArtifactSet(
                        needsPomRedirect = repositories.values.any {
                            "mavenPom" in it.metadataSources &&
                                    "ignoreGradleMetadataRedirection" !in it.metadataSources
                        },
                        needsIvyRedirect = repositories.values.any {
                            "ivyDescriptor" in it.metadataSources &&
                                    "ignoreGradleMetadataRedirection" !in it.metadataSources
                        },
                        files = (component?.artifacts ?: emptyList()).associate { meta ->
                            meta.name to ArtifactFile(
                                urls = dep.repositories
                                    .flatMap { repository -> artifactUrls(componentId, meta.name, repository, gradleModule) }
                                    .distinct(),
                                hash = meta.checksums.first().toSri()
                            )
                        }.toSortedMap()
                    )
                }
                .toSortedMap(Version.Comparator.reversed())
            Module(versions)
        }
        .toSortedMap(compareBy(ModuleId::toString))

    return Env(modules)
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
    id: ModuleVersionId,
): Component? {
    val cacheDir = with(id) { config.gradleHome.resolve("caches/modules-2/files-2.1/$group/$name/$version") }
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
    id: ModuleVersionId
): Component? {
    if (m2 == null) return null
    val dir = with(id) {
        File(URI.create(m2)).resolve("${group.replace(".", "/")}/$name/$version")
    }
    if (!dir.exists()) {
        config.logger.log("$id: not found in m2 repository; tried $dir")
        return null
    }
    val verifications = dir.walk().filter { it.isFile && it.name.startsWith(id.name) }.map { f ->
        ArtifactMetadata(
            f.name,
            sha256 = Sha256(f.sha256())
        )
    }
    config.logger.log("$id: obtained artifact hashes from test Maven repository.")
    return Component(id, verifications.toList())
}

@OptIn(ExperimentalSerializationApi::class)
private fun maybeGetGradleModule(logger: Logger, id: ModuleVersionId, repos: List<Repository>): GradleModule? {
    val filename = with(id) { "$name-$version.module" }
    val reposWithGradleMetadata = repos
        .filter { "gradleMetadata" in it.metadataSources }
        .flatMap { artifactUrls(id, filename, it, null)}

    for (url in reposWithGradleMetadata) {
        try {
            return URL(url).openStream().buffered().use { input ->
                JsonFormat.decodeFromStream(input)
            }
        } catch (e: SerializationException) {
            logger.error("$id: failed to parse Gradle module metadata ($url)", e)
        } catch (e: IOException) {
            // Pass
        }
    }

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
    id: ModuleVersionId,
    filename: String,
    repository: Repository,
    module: GradleModule?
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
        "module" to id.name,
        "revision" to id.version.toString(),
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
    }

    val urls = mutableListOf<String>()

    for (resource in resources) {
        val location = attributes.entries.fold(fill(resource, attributes)) { acc, (key, value) ->
            acc.replace("[$key]", value)
        }
        if (location.none { it == '[' || it == ']' }) {
            urls.add(location)
        }
    }

    return urls
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

// Gradle persists artifacts with the Maven artifact pattern, which may not match the repository's pattern.
private fun fileAttributes(file: String, version: Version): Map<String, String> {
    val parts = Regex("(.+)-$version(-([^.]+))?(\\.(.+))?").matchEntire(file) ?: return emptyMap()

    val (artifact, _, classifier, _, ext) = parts.destructured

    return buildMap {
        put("artifact", artifact)
        put("classifier", classifier)
        put("ext", ext)
    }
}

private data class MergedDependency(
    val id: ModuleVersionId,
    val repositories: List<Repository>
)
