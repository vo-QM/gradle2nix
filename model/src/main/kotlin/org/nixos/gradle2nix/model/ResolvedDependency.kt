package org.nixos.gradle2nix.model

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedDependency(
    val id: DependencyCoordinates,
    val source: DependencySource,
    val direct: Boolean,
    val repository: String?,
    val dependencies: List<String> = emptyList(),
)
