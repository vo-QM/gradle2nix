package org.nixos.gradle2nix.dependencygraph.model

import kotlinx.serialization.Serializable

@Serializable
data class DependencyCoordinates(val group: String, val module: String, val version: String) {
    override fun toString(): String = "$group:$module:$version"
}
