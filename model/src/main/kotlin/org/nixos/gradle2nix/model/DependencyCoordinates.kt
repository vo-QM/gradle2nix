package org.nixos.gradle2nix.model

import kotlinx.serialization.Serializable

@Serializable
data class DependencyCoordinates(
    val group: String,
    val module: String,
    val version: String,
    val timestamp: String? = null
) {
    override fun toString(): String = if (timestamp != null) {
        "$group:$module:$version:$timestamp"
    } else {
        "$group:$module:$version"
    }

    val isSnapshot: Boolean get() = timestamp != null
    val moduleVersion: String get() = version
    val artifactVersion: String get() =
        timestamp?.let { version.replace("SNAPSHOT", it) } ?: version

    companion object {
        fun parse(id: String): DependencyCoordinates {
            val parts = id.split(":")
            return when (parts.size) {
                3 -> DependencyCoordinates(parts[0], parts[1], parts[2])
                4 -> DependencyCoordinates(parts[0], parts[1], parts[2], parts[3])
                else -> throw IllegalStateException(
                    "couldn't parse dependency coordinates: '$id'"
                )
            }
        }
    }
}
