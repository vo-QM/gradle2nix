package org.nixos.gradle2nix

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.nixos.gradle2nix.model.PARAM_INCLUDE_CONFIGURATIONS
import org.nixos.gradle2nix.model.PARAM_INCLUDE_PROJECTS
import org.nixos.gradle2nix.model.RESOLVE_ALL_TASK

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
            if (config.gradleJdk != null) {
                setJavaHome(config.gradleJdk)
            }
            addArguments(config.gradleArgs)
            addArguments(
                "--gradle-user-home=${config.gradleHome}",
                "--init-script=${config.appHome}/init.gradle",
                "--write-verification-metadata", "sha256"
            )
            if (config.projectFilter != null) {
                addArguments("-D$PARAM_INCLUDE_PROJECTS")
            }
            if (config.configurationFilter != null) {
                addArguments("-D$PARAM_INCLUDE_CONFIGURATIONS")
            }
            if (config.logger.verbose) {
                setStandardOutput(System.err)
                setStandardError(System.err)
            }
            if (config.logger.stacktrace) {
                addArguments("--stacktrace")
            }
        }
        .run()
}
