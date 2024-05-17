package org.nixos.gradle2nix.forceresolve

import javax.inject.Inject
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.serialization.Cached
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Not worth caching")
abstract class ResolveProjectDependenciesTask @Inject constructor(
    private val objects: ObjectFactory
): AbstractResolveProjectDependenciesTask() {
    private val artifactFiles = Cached.of { artifactFiles() }

    private fun artifactFiles(): FileCollection {
        return objects.fileCollection().from(
            getReportableConfigurations().map { configuration ->
                configuration.incoming.artifactView { viewConfiguration ->
                    viewConfiguration.componentFilter { it is ModuleComponentIdentifier }
                }.files
            }
        )
    }

    @TaskAction
    fun action() {
        artifactFiles.get().count()
    }
}
