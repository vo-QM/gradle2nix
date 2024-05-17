package org.nixos.gradle2nix

import org.nixos.gradle2nix.metadata.Artifact as ArtifactMetadata
import java.io.File
import java.io.IOException
import java.net.URI
import okio.ByteString.Companion.decodeHex
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.source
import org.nixos.gradle2nix.env.Artifact
import org.nixos.gradle2nix.env.Env
import org.nixos.gradle2nix.metadata.Checksum
import org.nixos.gradle2nix.metadata.Component
import org.nixos.gradle2nix.metadata.Md5
import org.nixos.gradle2nix.metadata.Sha1
import org.nixos.gradle2nix.metadata.Sha256
import org.nixos.gradle2nix.metadata.Sha512
import org.nixos.gradle2nix.metadata.VerificationMetadata
import org.nixos.gradle2nix.metadata.parseVerificationMetadata
import org.nixos.gradle2nix.model.DependencyCoordinates
import org.nixos.gradle2nix.model.DependencySet

fun processDependencies(
    config: Config,
    dependencySets: Iterable<DependencySet>
): Env {
    val verificationMetadata = readVerificationMetadata(config)
    val verificationComponents = verificationMetadata?.components?.associateBy { it.id.id } ?: emptyMap()

    return buildMap<DependencyCoordinates, Map<String, Artifact>> {
        for (dependencySet in dependencySets) {
            val env = dependencySet.toEnv(config, verificationComponents)

            for ((id, artifacts) in env) {
                merge(id, artifacts) { a, b ->
                    buildMap {
                        putAll(a)
                        for ((name, artifact) in b) {
                            merge(name, artifact) { aa, ba ->
                                check(aa.hash == ba.hash) {
                                    config.logger.error("""
                                        Conflicting hashes found for $id:$name:
                                          1: ${aa.hash}
                                          2: ${ba.hash}
                                    """.trimIndent())
                                }

                                Artifact(
                                    (aa.urls + ba.urls).distinct().sorted(),
                                    aa.hash
                                )
                            }
                        }
                    }
                }
            }
        }
    }.mapValues { (_, artifacts) ->
        artifacts.toSortedMap()
    }.toSortedMap(coordinatesComparator)
        .mapKeys { (coordinates, _) -> coordinates.id }
}

private fun DependencySet.toEnv(config: Config, verificationComponents: Map<String, Component>): Map<DependencyCoordinates, Map<String, Artifact>> {
    return dependencies.associate { dep ->
        val component = verificationComponents[dep.coordinates.id]
            ?: verifyComponentFilesInCache(config, dep.coordinates)
            ?: config.logger.error("${dep.coordinates}: no dependency metadata found")

        dep.coordinates to dep.artifacts.mapNotNull { resolvedArtifact ->
            val artifact = component.artifacts.find { it.name == resolvedArtifact.name }
                ?.let { Artifact(resolvedArtifact.urls.sorted(), it.checksums.first().toSri()) }
                ?: downloadArtifact(resolvedArtifact.urls.sorted())
            artifact?.let { resolvedArtifact.filename to it }
        }.sortedBy { it.first }.toMap()
    }
}

private fun readVerificationMetadata(config: Config): VerificationMetadata? {
    return parseVerificationMetadata(config.logger, config.projectDir.resolve("gradle/verification-metadata.xml"))
}

private fun verifyComponentFilesInCache(
    config: Config,
    id: DependencyCoordinates,
): Component? {
    val cacheDir = with(id) { config.gradleHome.resolve("caches/modules-2/files-2.1/$group/$artifact/$version") }
    if (!cacheDir.exists()) {
        return null
    }
    val verifications = cacheDir.walk().filter { it.isFile }.map { f ->
        ArtifactMetadata(f.name.replaceFirst(id.version, id.timestampedVersion), sha256 = Sha256(f.sha256()))
    }
    config.logger.info("${id.id}: obtained artifact hashes from Gradle cache.")
    return Component(id, verifications.toList())
}

private fun downloadArtifact(
    urls: List<String>
): Artifact? {
    return maybeDownloadText(urls)?.let {
        Artifact(
            urls,
            it.hash.toSri()
        )
    }
}

private fun maybeDownloadText(
    urls: List<String>,
): ArtifactDownload<String>? {
    for (url in urls) {
        try {
            val source = HashingSource.sha256(URI(url).toURL().openStream().source())
            val text = source.buffer().readUtf8()
            val hash = source.hash
            return ArtifactDownload(text, url, Sha256(hash.hex()))
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

internal fun Checksum.toSri(): String {
    val hash = value.decodeHex().base64()
    return when (this) {
        is Md5 -> "md5-$hash"
        is Sha1 -> "sha1-$hash"
        is Sha256 -> "sha256-$hash"
        is Sha512 -> "sha512-$hash"
    }
}

private data class ArtifactDownload<T>(
    val artifact: T,
    val url: String,
    val hash: Checksum
)

private val coordinatesComparator: Comparator<DependencyCoordinates> = compareBy<DependencyCoordinates> { it.group }
    .thenBy { it.artifact }
    .thenByDescending { Version(it.version) }
    .thenByDescending { it.timestamp }
