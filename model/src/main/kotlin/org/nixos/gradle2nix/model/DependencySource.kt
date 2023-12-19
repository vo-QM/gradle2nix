package org.nixos.gradle2nix.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The source of a dependency declaration, representing where the direct dependency is declared,
 * or where the parent dependency is declared for transitive dependencies.
 * In most cases, this will be the project component that declares the dependency,
 * but may also be a Version Catalog or the build as a whole.
 * We attempt to map this to an actual source file location when building a dependency report.
 */
@Serializable
data class DependencySource(
    val targetType: ConfigurationTarget,
    val targetPath: String,
    val buildPath: String,
)

@Serializable
enum class ConfigurationTarget {
    @SerialName("gradle") GRADLE,
    @SerialName("settings") SETTINGS,
    @SerialName("buildscript") BUILDSCRIPT,
    @SerialName("project") PROJECT,
}
