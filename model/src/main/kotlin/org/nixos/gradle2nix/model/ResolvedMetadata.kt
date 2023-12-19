package org.nixos.gradle2nix.model

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedMetadata(
    val moduleId: String,
    val uri: String
)
