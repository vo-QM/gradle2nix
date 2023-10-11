@file:Suppress("UnstableApiUsage")

package org.nixos.gradle2nix

import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.nixos.gradle2nix.dependencygraph.AbstractDependencyExtractorPlugin
import org.nixos.gradle2nix.forceresolve.ForceDependencyResolutionPlugin

@Suppress("unused")
class Gradle2NixPlugin : Plugin<Gradle> {
    override fun apply(gradle: Gradle) {
        // Only apply the dependency extractor to the root build
        if (gradle.parent == null) {
            gradle.pluginManager.apply(NixDependencyExtractorPlugin::class.java)
        }
        gradle.pluginManager.apply(ForceDependencyResolutionPlugin::class.java)
    }

    class NixDependencyExtractorPlugin : AbstractDependencyExtractorPlugin() {
        override fun getRendererClassName(): String =
            NixDependencyGraphRenderer::class.java.name
    }
}
