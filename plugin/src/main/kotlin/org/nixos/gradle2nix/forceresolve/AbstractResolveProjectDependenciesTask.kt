package org.nixos.gradle2nix.forceresolve

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault
import org.nixos.gradle2nix.util.canSafelyBeResolved

@DisableCachingByDefault(because = "Not worth caching")
abstract class AbstractResolveProjectDependenciesTask : DefaultTask() {
    @Internal
    protected fun getReportableConfigurations(): List<Configuration> {
        return project.configurations.filter { it.canSafelyBeResolved() }
    }
}
