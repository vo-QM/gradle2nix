package org.nixos.gradle2nix

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection

fun connect(config: Config): ProjectConnection =
    GradleConnector.newConnector()
        .apply {
            if (config.gradleVersion != null) {
                useGradleVersion(config.gradleVersion)
            }
        }
        .forProjectDirectory(config.projectDir)
        .connect()

fun ProjectConnection.build(
    config: Config,
) {
    newBuild()
        .apply {
            if (config.tasks.isNotEmpty()) {
                forTasks(*config.tasks.toTypedArray())
            } else {
                forTasks(RESOLVE_ALL_TASK)
            }
            addArguments(config.gradleArgs)
            addArguments(
                "--init-script=${config.appHome}/init.gradle",
                "--write-verification-metadata", "sha256"
            )
            if (config.projectFilter != null) {
                addArguments("-D${PARAM_INCLUDE_PROJECTS}")
            }
            if (config.configurationFilter != null) {
                addArguments("-D${PARAM_INCLUDE_CONFIGURATIONS}")
            }
            if (config.logger.verbose) {
                setStandardOutput(System.err)
                setStandardError(System.err)
            }
        }
        .run()
}
