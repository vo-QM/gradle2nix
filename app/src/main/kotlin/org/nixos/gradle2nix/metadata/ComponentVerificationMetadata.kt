package org.nixos.gradle2nix.metadata

import org.nixos.gradle2nix.dependency.ModuleComponentIdentifier

data class ComponentVerificationMetadata(
    val componentId: ModuleComponentIdentifier,
    val artifactVerifications: List<ArtifactVerificationMetadata>,
)
