package org.nixos.gradle2nix.dependencygraph.model

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedConfiguration(
    val rootSource: DependencySource,
    val configurationName: String,
    val repositories: List<Repository> = emptyList(),
    val allDependencies: MutableList<ResolvedDependency> = mutableListOf()
) {
    fun addDependency(component: ResolvedDependency) {
        allDependencies.add(component)
    }

    fun hasDependency(componentId: String): Boolean {
        return allDependencies.any { it.id == componentId }
    }
}
