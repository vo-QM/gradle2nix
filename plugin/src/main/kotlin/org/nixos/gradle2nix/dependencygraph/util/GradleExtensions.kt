package org.nixos.gradle2nix.dependencygraph.util

import java.io.File
import org.gradle.api.Project
import org.gradle.api.internal.GradleInternal
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.internal.operations.BuildOperationListenerManager
import org.gradle.util.GradleVersion

internal abstract class GradleExtensions {

    inline val Gradle.providerFactory: ProviderFactory
        get() = service()

    inline val Gradle.buildOperationListenerManager: BuildOperationListenerManager
        get() = service()
}

internal inline fun <reified T> Gradle.service(): T =
    (this as GradleInternal).services.get(T::class.java)

internal val Project.buildDirCompat: File
    get() = if (GradleVersion.current() < GradleVersion.version("8.3")) {
        @Suppress("DEPRECATION")
        buildDir
    } else {
        layout.buildDirectory.asFile.get()
    }
