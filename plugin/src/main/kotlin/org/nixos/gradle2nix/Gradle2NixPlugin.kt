@file:Suppress("UnstableApiUsage")

package org.nixos.gradle2nix

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.invocation.Gradle
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.nixos.gradle2nix.dependencygraph.DependencyExtractor
import org.nixos.gradle2nix.forceresolve.ForceDependencyResolutionPlugin
import org.nixos.gradle2nix.model.DependencySet
import org.nixos.gradle2nix.util.artifactCachesProvider
import org.nixos.gradle2nix.util.buildOperationListenerManager
import org.nixos.gradle2nix.util.checksumService
import org.nixos.gradle2nix.util.fileStoreAndIndexProvider

abstract class Gradle2NixPlugin @Inject constructor(
    private val toolingModelBuilderRegistry: ToolingModelBuilderRegistry
): Plugin<Gradle> {

    override fun apply(gradle: Gradle) {
        val dependencyExtractor = DependencyExtractor(
            gradle.artifactCachesProvider,
            gradle.checksumService,
            gradle.fileStoreAndIndexProvider,
        )

        toolingModelBuilderRegistry.register(DependencySetModelBuilder(dependencyExtractor))

        gradle.buildOperationListenerManager.addListener(dependencyExtractor)

        // Configuration caching is not enabled with dependency verification so this is fine for now.
        // Gradle 9.x might remove this though.
        @Suppress("DEPRECATION")
        gradle.buildFinished {
            gradle.buildOperationListenerManager.removeListener(dependencyExtractor)
        }

        gradle.pluginManager.apply(ForceDependencyResolutionPlugin::class.java)
    }
}

internal class DependencySetModelBuilder(
    private val dependencyExtractor: DependencyExtractor,
) : ToolingModelBuilder {

    override fun canBuild(modelName: String): Boolean = modelName == DependencySet::class.qualifiedName

    override fun buildAll(modelName: String, project: Project): DependencySet {
        return dependencyExtractor.buildDependencySet()
    }
}
