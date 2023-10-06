package org.nixos.gradle2nix

import kotlinx.serialization.Serializable

@Serializable
data class Artifact(
    val urls: List<String>,
    val hash: String,
)
