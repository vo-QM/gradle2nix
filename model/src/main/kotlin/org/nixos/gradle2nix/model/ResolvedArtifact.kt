package org.nixos.gradle2nix.model

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedArtifact(
    val type: Type?,
    val file: String,
) {
    enum class Type {
        SOURCES,
        JAVADOC,
        IVY_DESCRIPTOR,
        MAVEN_POM,
    }
}
