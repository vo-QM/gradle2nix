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
import org.nixos.gradle2nix.dependencygraph.model.DependencyCoordinates
import org.nixos.gradle2nix.dependencygraph.model.Repository
import org.nixos.gradle2nix.dependencygraph.model.ResolvedConfiguration
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

fun processDependencies(config: Config): Map<String, Map<String, Artifact>> {
    val verificationMetadata = readVerificationMetadata(config)
    val verificationComponents = verificationMetadata?.components?.associateBy {
        DependencyCoordinates(it.group, it.name, it.version)
    } ?: emptyMap()
    val moduleCache = mutableMapOf<DependencyCoordinates, GradleModule?>()
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

    return configurations.asSequence()
        .flatMap { it.allDependencies.asSequence() }
        .groupBy { it.id }
        .mapNotNull { (id, dependencies) ->
            if (id.startsWith("project ")) return@mapNotNull null
            val deps = dependencies.toSet()
            if (deps.isEmpty()) {
                config.logger.warn("$id: no resolved dependencies in dependency graph")
                return@mapNotNull null
            }
            val coordinates = deps.first().coordinates
            val component = verificationComponents[coordinates]
                ?: verifyComponentFilesInCache(config, coordinates)
                ?: verifyComponentFilesInTestRepository(config, coordinates)
            if (component == null) {
                config.logger.warn("$id: not present in metadata or cache; skipping")
                return@mapNotNull null
            }

            val repoIds = dependencies.mapNotNull { it.repository }
            if (repoIds.isEmpty()) {
                config.logger.warn("$id: no repository ids in dependency graph; skipping")
                return@mapNotNull null
            }
            val repos = repoIds.mapNotNull(repositories::get)
            if (repos.isEmpty()) {
                config.logger.warn("$id: no repositories found for repository ids $repoIds; skipping")
                return@mapNotNull null
            }

            val gradleModule = moduleCache.getOrPut(coordinates) {
                maybeGetGradleModule(config.logger, coordinates, repos)
            }

            id to component.artifacts.associate { meta ->
                meta.name to Artifact(
                    urls = repos
                        .flatMap { repository -> artifactUrls(coordinates, meta.name, repository, gradleModule) }
                        .distinct(),
                    hash = meta.checksums.first().toSri()
                )
            }
        }
        .sortedBy { it.first }
        .toMap()
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
    coordinates: DependencyCoordinates,
): Component? {
    val cacheDir = with(coordinates) { config.gradleHome.resolve("caches/modules-2/files-2.1/$group/$module/$version") }
    if (!cacheDir.exists()) {
        return null
    }
    val verifications = cacheDir.walk().filter { it.isFile }.map { f ->
        ArtifactMetadata(f.name, sha256 = Sha256(f.sha256()))
    }
    config.logger.log("$coordinates: obtained artifact hashes from Gradle cache.")
    return Component(coordinates, verifications.toList())
}

private fun verifyComponentFilesInTestRepository(
    config: Config,
    coordinates: DependencyCoordinates
): Component? {
    if (m2 == null) return null
    val dir = with(coordinates) {
        File(URI.create(m2)).resolve("${group.replace(".", "/")}/$module/$version")
    }
    if (!dir.exists()) {
        config.logger.log("$coordinates: not found in m2 repository; tried $dir")
        return null
    }
    val verifications = dir.walk().filter { it.isFile && it.name.startsWith(coordinates.module) }.map { f ->
        ArtifactMetadata(
            f.name,
            sha256 = Sha256(f.sha256())
        )
    }
    config.logger.log("$coordinates: obtained artifact hashes from test Maven repository.")
    return Component(coordinates, verifications.toList())
}

@OptIn(ExperimentalSerializationApi::class)
private fun maybeGetGradleModule(logger: Logger, coordinates: DependencyCoordinates, repos: List<Repository>): GradleModule? {
    val filename = with(coordinates) { "$module-$version.module" }

    for (url in repos.flatMap { artifactUrls(coordinates, filename, it, null)}) {
        try {
            return URL(url).openStream().buffered().use { input ->
                JsonFormat.decodeFromStream(input)
            }
        } catch (e: SerializationException) {
            logger.error("$coordinates: failed to parse Gradle module metadata ($url)", e)
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
    coordinates: DependencyCoordinates,
    filename: String,
    repository: Repository,
    module: GradleModule?
): List<String> {
    val groupAsPath = coordinates.group.replace(".", "/")

    val repoFilename = module?.let { m ->
        m.variants
            .asSequence()
            .flatMap(Variant::files)
            .find { it.name == filename }
    }?.url ?: filename

    val attributes = mutableMapOf(
        "organisation" to if (repository.m2Compatible) groupAsPath else coordinates.group,
        "module" to coordinates.module,
        "revision" to coordinates.version,
    ) + fileAttributes(repoFilename, coordinates.version)

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
private fun fileAttributes(file: String, version: String): Map<String, String> {
    val parts = Regex("(.+)-$version(-([^.]+))?(\\.(.+))?").matchEntire(file) ?: return emptyMap()

    val (artifact, _, classifier, _, ext) = parts.destructured

    return buildMap {
        put("artifact", artifact)
        put("classifier", classifier)
        put("ext", ext)
    }
}
