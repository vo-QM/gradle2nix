package org.nixos.gradle2nix.metadata

data class ArtifactVerificationMetadata(
    val artifactName: String,
    val checksums: List<Checksum>
)
