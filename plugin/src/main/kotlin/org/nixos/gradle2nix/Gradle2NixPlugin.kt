@file:Suppress("UnstableApiUsage")

package org.nixos.gradle2nix

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.ivyservice.ArtifactCachesProvider
import org.gradle.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider
import org.gradle.api.invocation.Gradle
import org.gradle.internal.hash.ChecksumService
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.GradleVersion
import org.nixos.gradle2nix.model.DependencySet
import org.nixos.gradle2nix.model.RESOLVE_ALL_TASK
import javax.inject.Inject

abstract class Gradle2NixPlugin
    @Inject
    constructor(
        private val toolingModelBuilderRegistry: ToolingModelBuilderRegistry,
    ) : Plugin<Gradle> {
        override fun apply(gradle: Gradle) {
            val dependencyExtractor = DependencyExtractor()

            toolingModelBuilderRegistry.register(
                DependencySetModelBuilder(
                    dependencyExtractor,
                    gradle.artifactCachesProvider,
                    gradle.checksumService,
                    gradle.fileStoreAndIndexProvider,
                ),
            )

            if (GradleVersion.current() < GradleVersion.version("8.0")) {
                val extractor = DependencyExtractor()
                gradle.buildOperationListenerManager.addListener(extractor)

                @Suppress("DEPRECATION")
                gradle.buildFinished {
                    gradle.buildOperationListenerManager.removeListener(extractor)
                }
            } else {
                val serviceProvider =
                    gradle.sharedServices.registerIfAbsent(
                        "nixDependencyExtractor",
                        DependencyExtractorService::class.java,
                    ).map { service ->
                        service.apply { extractor = dependencyExtractor }
                    }

                gradle.buildEventListenerRegistryInternal.onOperationCompletion(serviceProvider)
            }

            gradle.projectsEvaluated {
                val resolveAll = gradle.rootProject.tasks.register(RESOLVE_ALL_TASK)

                // Depend on "dependencies" task in all projects
                gradle.allprojects { project ->
                    val resolveProject = project.createResolveTask()
                    resolveAll.configure { it.dependsOn(resolveProject) }
                }

                // Depend on all 'resolveBuildDependencies' task in each included build
                gradle.includedBuilds.forEach { includedBuild ->
                    resolveAll.configure {
                        it.dependsOn(includedBuild.task(":$RESOLVE_ALL_TASK"))
                    }
                }
            }
        }
    }

internal class DependencySetModelBuilder(
    private val dependencyExtractor: DependencyExtractor,
    private val artifactCachesProvider: ArtifactCachesProvider,
    private val checksumService: ChecksumService,
    private val fileStoreAndIndexProvider: FileStoreAndIndexProvider,
) : ToolingModelBuilder {
    override fun canBuild(modelName: String): Boolean = modelName == DependencySet::class.qualifiedName

    override fun buildAll(
        modelName: String,
        project: Project,
    ): DependencySet {
        return dependencyExtractor.buildDependencySet(
            artifactCachesProvider,
            checksumService,
            fileStoreAndIndexProvider,
        )
    }
}
