package org.nixos.gradle2nix.model

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedDependency(
    val id: String,
    val source: DependencySource,
    val direct: Boolean,
    val coordinates: DependencyCoordinates,
    val repository: String?,
    val dependencies: List<String> = emptyList(),
)
