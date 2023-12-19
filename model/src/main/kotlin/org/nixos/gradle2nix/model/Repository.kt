package org.nixos.gradle2nix.model

import kotlinx.serialization.Serializable

@Serializable
data class Repository(
    val id: String,
    val type: Type,
    val name: String,
    val m2Compatible: Boolean,
    val metadataSources: List<String>,
    val metadataResources: List<String>,
    val artifactResources: List<String>,
) {
    enum class Type {
        MAVEN,
        IVY,
        FLAT_DIR
    }
}
