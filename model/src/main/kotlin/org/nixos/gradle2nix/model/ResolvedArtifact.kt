package org.nixos.gradle2nix.model

import java.io.Serializable

interface ResolvedArtifact : Serializable {
    val name: String
    val filename: String
    val urls: List<String>
}
