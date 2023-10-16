package org.nixos.gradle2nix.dependencygraph.model

import kotlinx.serialization.Serializable
import org.nixos.gradle2nix.DependencyCoordinates

@Serializable
data class ResolvedDependency(
    val id: String,
    val source: DependencySource,
    val direct: Boolean,
    val coordinates: DependencyCoordinates,
    val repository: String?,
    val dependencies: List<String>
)
