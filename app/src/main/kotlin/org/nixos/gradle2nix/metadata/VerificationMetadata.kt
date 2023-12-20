package org.nixos.gradle2nix.metadata

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.xmlStreaming
import org.nixos.gradle2nix.Logger
import org.nixos.gradle2nix.model.DependencyCoordinates

sealed interface Coordinates {
    val group: String?
    val name: String?
    val version: String?
    val regex: Boolean
    val file: String?
}

@Serializable
@SerialName("trust")
data class Trust(
    override val group: String? = null,
    override val name: String? = null,
    override val version: String? = null,
    override val regex: Boolean = false,
    override val file: String? = null,
    val reason: String? = null,
) : Coordinates

@Serializable
@SerialName("configuration")
data class Configuration(
    @SerialName("verify-metadata") @XmlElement(true) val verifyMetadata: Boolean = false,
    @SerialName("verify-signatures") @XmlElement(true) val verifySignatures: Boolean = false,
    @SerialName("trusted-artifacts") @XmlChildrenName("trusted-artifacts") val trustedArtifacts: List<Trust> = emptyList()
)

@Serializable
sealed interface Checksum {
    abstract val value: String
    abstract val origin: String?
    abstract val reason: String?
    abstract val alternatives: List<String>
}

@Serializable
@SerialName("md5")
data class Md5(
    override val value: String,
    override val origin: String? = null,
    override val reason: String? = null,
    @XmlChildrenName("also-trust")
    override val alternatives: List<String> = emptyList()
) : Checksum

@Serializable
@SerialName("sha1")
data class Sha1(
    override val value: String,
    override val origin: String? = null,
    override val reason: String? = null,
    @XmlChildrenName("also-trust")
    override val alternatives: List<String> = emptyList()
) : Checksum

@Serializable
@SerialName("sha256")
data class Sha256(
    override val value: String,
    override val origin: String? = null,
    override val reason: String? = null,
    @XmlChildrenName("also-trust")
    override val alternatives: List<String> = emptyList()
) : Checksum

@Serializable
@SerialName("sha512")
data class Sha512(
    override val value: String,
    override val origin: String? = null,
    override val reason: String? = null,
    @XmlChildrenName("also-trust")
    override val alternatives: List<String> = emptyList()
) : Checksum

@Serializable
@SerialName("artifact")
data class Artifact(
    val name: String,
    val md5: Md5? = null,
    val sha1: Sha1? = null,
    val sha256: Sha256? = null,
    val sha512: Sha512? = null,
) {
    val checksums: List<Checksum> by lazy { listOfNotNull(sha512, sha256, sha1, md5) }
}

@Serializable
@SerialName("component")
data class Component(
    val group: String,
    val name: String,
    val version: String,
    val timestamp: String? = null,
    val artifacts: List<Artifact> = emptyList(),
) {
    val id: DependencyCoordinates get() = DependencyCoordinates(group, name, version, timestamp)

    constructor(id: DependencyCoordinates, artifacts: List<Artifact>) : this(
        id.group,
        id.module,
        id.version,
        id.timestamp,
        artifacts
    )
}

@Serializable
@XmlSerialName(
    "verification-metadata",
    namespace = "https://schema.gradle.org/dependency-verification",
    prefix = ""
)
data class VerificationMetadata(
    val configuration: Configuration = Configuration(),
    @XmlChildrenName("components", "https://schema.gradle.org/dependency-verification") val components: List<Component> = emptyList()
)

val XmlFormat = XML {
    autoPolymorphic = true
    recommended()
}

fun parseVerificationMetadata(logger: Logger, metadata: File): VerificationMetadata? {
    return try {
        metadata.reader().buffered().let(xmlStreaming::newReader).use { input ->
            XmlFormat.decodeFromReader(input)
        }
    } catch (e: Exception) {
        logger.warn("$metadata: failed to parse Gradle dependency verification metadata", e)
        return null
    }
}
