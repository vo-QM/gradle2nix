package org.nixos.gradle2nix.gradle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GradleModule(
    val formatVersion: String,
    val component: Component? = null,
    val createdBy: CreatedBy? = null,
    val variants: List<Variant> = emptyList(),
)

@Serializable
data class Component(
    val group: String,
    val module: String,
    val version: String,
    val url: String? = null,
)

@Serializable
data class Gradle(
    val version: String,
    val buildId: String? = null
)

@Serializable
data class CreatedBy(
    val gradle: Gradle? = null
)

@Serializable
data class Variant(
    val name: String,
    val attributes: JsonObject? = null,
    @SerialName("available-at") val availableAt: AvailableAt? = null,
    val dependencies: List<Dependency> = emptyList(),
    val dependencyConstraints: List<DependencyConstraint> = emptyList(),
    val files: List<VariantFile> = emptyList(),
    val capabilities: List<Capability> = emptyList()
)

@Serializable
data class AvailableAt(
    val url: String,
    val group: String,
    val module: String,
    val version: String,
)

@Serializable
data class Dependency(
    val group: String,
    val module: String,
    val version: JsonObject? = null,
    val excludes: List<Exclude> = emptyList(),
    val reason: String? = null,
    val attributes: JsonObject? = null,
    val requestedCapabilities: List<Capability> = emptyList(),
    val endorseStrictVersions: Boolean = false,
    val thirdPartyCompatibility: ThirdPartyCompatibility? = null,
)

@Serializable
data class DependencyConstraint(
    val group: String,
    val module: String,
    val version: JsonObject? = null,
    val reason: String? = null,
    val attributes: JsonObject? = null,
)

@Serializable
data class VariantFile(
    val name: String,
    val url: String,
    val size: Long,
    val sha1: String? = null,
    val sha256: String? = null,
    val sha512: String? = null,
    val md5: String? = null,
)

@Serializable
data class Capability(
    val group: String,
    val name: String,
    val version: String? = null,
)

@Serializable
data class Exclude(
    val group: String,
    val module: String,
)

@Serializable
data class ThirdPartyCompatibility(
    val artifactSelector: String
)
