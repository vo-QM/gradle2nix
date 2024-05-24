package org.nixos.gradle2nix

import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.suspendCancellableCoroutine
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.model.gradle.GradleBuild
import org.nixos.gradle2nix.model.DependencySet
import org.nixos.gradle2nix.model.RESOLVE_ALL_TASK

fun connect(config: Config, projectDir: File = config.projectDir): ProjectConnection =
    GradleConnector.newConnector()
        .apply {
            if (config.gradleVersion != null) {
                useGradleVersion(config.gradleVersion)
            }
        }
        .forProjectDirectory(projectDir)
        .connect()

suspend fun ProjectConnection.buildModel(): GradleBuild = suspendCancellableCoroutine { continuation ->
    val cancellationTokenSource = GradleConnector.newCancellationTokenSource()

    continuation.invokeOnCancellation { cancellationTokenSource.cancel() }

    action { controller -> controller.buildModel }
        .withCancellationToken(cancellationTokenSource.token())
        .run(object : ResultHandler<GradleBuild> {
            override fun onComplete(result: GradleBuild) {
                continuation.resume(result)
            }

            override fun onFailure(failure: GradleConnectionException) {
                continuation.resumeWithException(failure)
            }
        })
}

suspend fun ProjectConnection.build(config: Config): DependencySet = suspendCancellableCoroutine { continuation ->
    val cancellationTokenSource = GradleConnector.newCancellationTokenSource()

    continuation.invokeOnCancellation { cancellationTokenSource.cancel() }

    action { controller -> controller.getModel(DependencySet::class.java) }
        .withCancellationToken(cancellationTokenSource.token())
        .apply {
            if (config.tasks.isNotEmpty()) {
                forTasks(*config.tasks.toTypedArray())
            } else {
                forTasks(RESOLVE_ALL_TASK)
            }
        }
        .setJavaHome(config.gradleJdk)
        .addArguments(config.gradleArgs)
        .addArguments(
            "--no-parallel",
            "--refresh-dependencies",
            "--gradle-user-home=${config.gradleHome}",
            "--init-script=${config.appHome}/init.gradle",
        )
        .apply {
            if (config.logger.stacktrace) {
                addArguments("--stacktrace")
            }
            if (config.logger.logLevel < LogLevel.error) {
                setStandardOutput(System.err)
                setStandardError(System.err)
            }
            if (config.dumpEvents) {
                withSystemProperties(
                    mapOf(
                        "org.gradle.internal.operations.trace" to
                                config.outDir.toPath().resolve("debug").absolutePathString()
                    )
                )
            }
        }
        .run(object : ResultHandler<DependencySet> {
            override fun onComplete(result: DependencySet) {
                continuation.resume(result)
            }

            override fun onFailure(failure: GradleConnectionException) {
                continuation.resumeWithException(failure)
            }
        })
}
