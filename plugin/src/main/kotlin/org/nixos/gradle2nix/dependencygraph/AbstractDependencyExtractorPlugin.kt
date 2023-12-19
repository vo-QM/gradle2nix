package org.nixos.gradle2nix.dependencygraph

import org.gradle.api.Plugin
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.project.DefaultProjectRegistry
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.ProjectRegistry
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.services.internal.RegisteredBuildServiceProvider
import org.gradle.internal.build.BuildProjectRegistry
import org.gradle.internal.build.event.BuildEventListenerRegistryInternal
import org.gradle.internal.composite.IncludedBuildInternal
import org.gradle.internal.operations.BuildOperationAncestryTracker
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.util.GradleVersion
import org.nixos.gradle2nix.dependencygraph.extractor.DependencyExtractor
import org.nixos.gradle2nix.dependencygraph.extractor.DependencyExtractorBuildService
import org.nixos.gradle2nix.dependencygraph.extractor.LegacyDependencyExtractor
import org.nixos.gradle2nix.dependencygraph.util.buildDirCompat
import org.nixos.gradle2nix.dependencygraph.util.service
import org.nixos.gradle2nix.model.ConfigurationTarget

abstract class AbstractDependencyExtractorPlugin : Plugin<Gradle> {
    // Register extension functions on `Gradle` type
    private companion object : org.nixos.gradle2nix.dependencygraph.util.GradleExtensions()

    /**
     * The name of an accessible class that implements `org.gradle.dependencygraph.DependencyGraphRenderer`.
     */
    abstract fun getRendererClassName(): String

    internal lateinit var dependencyExtractorProvider: Provider<out DependencyExtractor>

    override fun apply(gradle: Gradle) {
        val gradleVersion = GradleVersion.current()
        // Create the adapter based upon the version of Gradle
        val applicatorStrategy = when {
            gradleVersion < GradleVersion.version("8.0") -> PluginApplicatorStrategy.LegacyPluginApplicatorStrategy
            else -> PluginApplicatorStrategy.DefaultPluginApplicatorStrategy
        }

        // Create the service
        dependencyExtractorProvider = applicatorStrategy.createExtractorService(gradle, getRendererClassName())

        gradle.rootProject { project ->
            dependencyExtractorProvider
                .get()
                .rootProjectBuildDirectory = project.buildDirCompat
        }

        val logger = Logging.getLogger(AbstractDependencyExtractorPlugin::class.java.name)

        gradle.projectsLoaded {
            (gradle as GradleInternal).let { g ->
                logger.lifecycle("all projects: ${g.owner.projects.allProjects}")
                logger.lifecycle("included projects: ${g.includedBuilds().flatMap { it.target.projects.allProjects }.joinToString { it.identityPath.path }}")
            }
        }

        // Register the service to listen for Build Events
        applicatorStrategy.registerExtractorListener(gradle, dependencyExtractorProvider)

        // Register the shutdown hook that should execute at the completion of the Gradle build.
        applicatorStrategy.registerExtractorServiceShutdown(gradle, dependencyExtractorProvider)
    }

    /**
     * Adapters for creating the [DependencyExtractor] and installing it into [Gradle] based upon the Gradle version.
     */
    private interface PluginApplicatorStrategy {

        fun createExtractorService(
            gradle: Gradle,
            rendererClassName: String
        ): Provider<out DependencyExtractor>

        fun registerExtractorListener(
            gradle: Gradle,
            extractorServiceProvider: Provider<out DependencyExtractor>
        )

        fun registerExtractorServiceShutdown(
            gradle: Gradle,
            extractorServiceProvider: Provider<out DependencyExtractor>
        )

        @Suppress("DEPRECATION")
        object LegacyPluginApplicatorStrategy : PluginApplicatorStrategy {

            override fun createExtractorService(
                gradle: Gradle,
                rendererClassName: String
            ): Provider<out DependencyExtractor> {
                val dependencyExtractor = LegacyDependencyExtractor(rendererClassName)
                return gradle.providerFactory.provider { dependencyExtractor }
            }

            override fun registerExtractorListener(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
                gradle.buildOperationListenerManager
                    .addListener(extractorServiceProvider.get())
            }

            override fun registerExtractorServiceShutdown(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
                gradle.buildFinished {
                    extractorServiceProvider.get().close()
                    gradle.buildOperationListenerManager
                        .removeListener(extractorServiceProvider.get())
                }
            }
        }

        object DefaultPluginApplicatorStrategy : PluginApplicatorStrategy {
            private const val SERVICE_NAME = "dependencyExtractorService"

            override fun createExtractorService(
                gradle: Gradle,
                rendererClassName: String
            ): Provider<out DependencyExtractor> {
                return gradle.sharedServices.registerIfAbsent(
                    SERVICE_NAME,
                    DependencyExtractorBuildService::class.java
                ) { it.parameters.rendererClassName.set(rendererClassName) }
            }

            override fun registerExtractorListener(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
                gradle.service<BuildEventListenerRegistryInternal>()
                    .onOperationCompletion(extractorServiceProvider)
            }

            override fun registerExtractorServiceShutdown(
                gradle: Gradle,
                extractorServiceProvider: Provider<out DependencyExtractor>
            ) {
            }
        }
    }
}
