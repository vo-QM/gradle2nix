package org.nixos.gradle2nix.model

import java.io.Serializable

interface Repository : Serializable {
    val id: String
    val type: Type
    val metadataSources: List<String>
    val metadataResources: List<String>
    val artifactResources: List<String>

    enum class Type {
        MAVEN,
        IVY,
    }
}
