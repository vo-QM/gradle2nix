package org.nixos.gradle2nix.env

import kotlinx.serialization.Serializable

typealias Env = Map<String, Map<String, Artifact>>

@Serializable
data class Artifact internal constructor(
    val urls: List<String>,
    val hash: String,
) {

    companion object {
        operator fun invoke(
            urls: List<String>,
            hash: String
        ) = Artifact(
            urls.sorted(),
            hash
        )
    }
}
