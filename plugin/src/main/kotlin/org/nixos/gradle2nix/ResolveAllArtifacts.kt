package org.nixos.gradle2nix

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.serialization.Cached
import org.gradle.work.DisableCachingByDefault
import org.nixos.gradle2nix.model.RESOLVE_PROJECT_TASK
import javax.inject.Inject

internal fun Project.createResolveTask(): TaskProvider<out Task> {
    return if (gradleVersionIsAtLeast8) {
        tasks.register(RESOLVE_PROJECT_TASK, ResolveProjectDependenciesTask::class.java)
    } else {
        tasks.register(RESOLVE_PROJECT_TASK, LegacyResolveProjectDependenciesTask::class.java)
    }
}

@DisableCachingByDefault(because = "Not worth caching")
sealed class AbstractResolveProjectDependenciesTask : DefaultTask() {
    @Internal
    protected fun getReportableConfigurations(): List<Configuration> {
        return project.configurations.filter { it.canSafelyBeResolved() }
    }
}

@DisableCachingByDefault(because = "Not worth caching")
abstract class LegacyResolveProjectDependenciesTask : AbstractResolveProjectDependenciesTask() {
    @TaskAction
    fun action() {
        for (configuration in getReportableConfigurations()) {
            configuration.incoming.resolutionResult.root
        }
    }
}

@DisableCachingByDefault(because = "Not worth caching")
abstract class ResolveProjectDependenciesTask
    @Inject
    constructor(
        private val objects: ObjectFactory,
    ) : AbstractResolveProjectDependenciesTask() {
        private val artifactFiles = Cached.of { artifactFiles() }

        private fun artifactFiles(): FileCollection {
            return objects.fileCollection().from(
                getReportableConfigurations().map { configuration ->
                    configuration.incoming.artifactView { viewConfiguration ->
                        viewConfiguration.componentFilter { it is ModuleComponentIdentifier }
                    }.files
                },
            )
        }

        @TaskAction
        fun action() {
            artifactFiles.get().count()
        }
    }
