package org.nixos.gradle2nix.util

import java.lang.reflect.Method
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.GradleInternal
import org.gradle.api.invocation.Gradle
import org.gradle.internal.operations.BuildOperationAncestryTracker
import org.gradle.internal.operations.BuildOperationListenerManager

internal inline val Gradle.buildOperationAncestryTracker: BuildOperationAncestryTracker
    get() = service()

internal inline val Gradle.buildOperationListenerManager: BuildOperationListenerManager
    get() = service()

internal inline fun <reified T> Gradle.service(): T =
    (this as GradleInternal).services.get(T::class.java)

private val canSafelyBeResolvedMethod: Method? = try {
    val dc = Class.forName("org.gradle.internal.deprecation.DeprecatableConfiguration")
    dc.getMethod("canSafelyBeResolved")
} catch (e: ReflectiveOperationException) {
    null
}

internal fun Configuration.canSafelyBeResolved(): Boolean =
    canSafelyBeResolvedMethod?.invoke(this) as? Boolean ?: isCanBeResolved
