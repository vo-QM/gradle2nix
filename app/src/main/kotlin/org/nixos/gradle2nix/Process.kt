package org.nixos.gradle2nix

import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okio.ByteString.Companion.decodeHex
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.source
import org.nixos.gradle2nix.dependency.ModuleComponentIdentifier
import org.nixos.gradle2nix.dependencygraph.model.DependencyCoordinates
import org.nixos.gradle2nix.dependencygraph.model.Repository
import org.nixos.gradle2nix.dependencygraph.model.ResolvedConfiguration
import org.nixos.gradle2nix.metadata.ArtifactVerificationMetadata
import org.nixos.gradle2nix.metadata.Checksum
import org.nixos.gradle2nix.metadata.ChecksumKind
import org.nixos.gradle2nix.metadata.ComponentVerificationMetadata
import org.nixos.gradle2nix.metadata.DependencyVerificationsXmlReader
import org.nixos.gradle2nix.metadata.DependencyVerifier

// Local Maven repository for testing
private val m2 = System.getProperty("org.nixos.gradle2nix.m2")

private fun shouldSkipRepository(repository: Repository): Boolean {
    return repository.artifactResources.all { it.startsWith("file:") && (m2 == null || !it.startsWith(m2)) } ||
            repository.metadataResources.all { it.startsWith("file:") && (m2 == null || !it.startsWith(m2)) }
}

fun processDependencies(config: Config): Map<String, Map<String, Artifact>> {
    val verifier = readVerificationMetadata(config)
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
            val deps = dependencies.toSet()
            if (deps.isEmpty()) {
                config.logger.warn("$id: no resolved dependencies in dependency graph")
                return@mapNotNull null
            }
            val coordinates = deps.first().coordinates
            val componentId = ModuleComponentIdentifier(
                coordinates.group,
                coordinates.module,
                coordinates.version
            )
            val metadata = verifier.verificationMetadata[componentId]
                ?: verifyComponentFilesInCache(config, componentId)
            if (metadata == null) {
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

            id to metadata.artifactVerifications.associate { meta ->
                meta.artifactName to Artifact(
                    urls = repos
                        .flatMap { repository -> artifactUrls(coordinates, meta, repository) }
                        .distinct(),
                    hash = meta.checksums.maxBy { c -> c.kind.ordinal }.toSri()
                )
            }
        }
        .toMap()
}

private fun readVerificationMetadata(config: Config): DependencyVerifier {
    return config.projectDir.resolve("gradle/verification-metadata.xml")
        .inputStream()
        .buffered()
        .use { input -> DependencyVerificationsXmlReader.readFromXml(input) }
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
    component: ModuleComponentIdentifier
): ComponentVerificationMetadata? {
    val cacheDir = config.gradleHome.resolve("caches/modules-2/files-2.1/${component.group}/${component.module}/${component.version}")
    if (!cacheDir.exists()) {
        return null
    }
    val verifications = cacheDir.walkBottomUp().filter { it.isFile }.map { f ->
        ArtifactVerificationMetadata(
            f.name,
            listOf(Checksum(ChecksumKind.sha256, f.sha256()))
        )
    }
    config.logger.log("$component: obtained artifact hashes from Gradle cache.")
    return ComponentVerificationMetadata(component, verifications.toList())
}

private fun File.sha256(): String {
    val source = HashingSource.sha256(source())
    source.buffer().readAll(blackholeSink())
    return source.hash.hex()
}

private fun Checksum.toSri(): String {
    val hash = value.decodeHex().base64()
    return when (kind) {
        ChecksumKind.md5 -> "md5-$hash"
        ChecksumKind.sha1 -> "sha1-$hash"
        ChecksumKind.sha256 -> "sha256-$hash"
        ChecksumKind.sha512 -> "sha512-$hash"
    }
}

private fun artifactUrls(
    coordinates: DependencyCoordinates,
    metadata: ArtifactVerificationMetadata,
    repository: Repository
): List<String> {
    val groupAsPath = coordinates.group.replace(".", "/")

    val attributes = mutableMapOf(
        "organisation" to if (repository.m2Compatible) groupAsPath else coordinates.group,
        "module" to coordinates.module,
        "revision" to coordinates.version,
    ) + fileAttributes(metadata.artifactName, coordinates.version)

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
