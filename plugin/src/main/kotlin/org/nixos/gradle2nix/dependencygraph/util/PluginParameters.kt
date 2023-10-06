package org.nixos.gradle2nix.dependencygraph.util

internal fun loadOptionalParam(envName: String): String? {
    return System.getProperty(envName)
        ?: System.getenv()[envName]
}
