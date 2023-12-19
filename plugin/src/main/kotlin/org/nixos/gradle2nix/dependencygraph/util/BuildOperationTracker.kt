package org.nixos.gradle2nix.dependencygraph.util

import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.logging.Logging
import org.gradle.internal.operations.BuildOperation
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationListener
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationProgressEvent
import org.gradle.internal.operations.OperationStartEvent

class BuildOperationTracker : BuildOperationListener {
    private val _parents: MutableMap<OperationIdentifier, OperationIdentifier?> = ConcurrentHashMap()
    private val _operations: MutableMap<OperationIdentifier, BuildOperationDescriptor> = ConcurrentHashMap()
    private val _results: MutableMap<OperationIdentifier, Any> = ConcurrentHashMap()

    val parents: Map<OperationIdentifier, OperationIdentifier?> get() = _parents
    val operations: Map<OperationIdentifier, BuildOperationDescriptor> get() = _operations
    val results: Map<OperationIdentifier, Any> get() = _results

    override fun started(buildOperation: BuildOperationDescriptor, startEvent: OperationStartEvent) {
    }

    override fun progress(operationIdentifier: OperationIdentifier, progressEvent: OperationProgressEvent) {
    }

    override fun finished(buildOperation: BuildOperationDescriptor, finishEvent: OperationFinishEvent) {
        val id = buildOperation.id ?: return
        _parents[id] = buildOperation.parentId
        _operations[id] = buildOperation
    }

    tailrec fun <T> findParent(id: OperationIdentifier?, block: (BuildOperationDescriptor) -> T?): T? {
        if (id == null) return null
        val operation = _operations[id] ?: return null.also {
            LOGGER.lifecycle("no operation for $id")
        }
        return block(operation) ?: findParent(operation.parentId, block)
    }

    fun <T> findChild(id: OperationIdentifier?, block: (BuildOperationDescriptor) -> T?): T? {
        if (id == null) return null
        val operation = operations[id] ?: return null
        block(operation)?.let { return it }
        return children(id).firstNotNullOfOrNull { findChild(it, block) }
    }

    fun children(id: OperationIdentifier): Set<OperationIdentifier> {
        return parents.filterValues { it == id }.keys
    }

    inline fun <reified T> getDetails(id: OperationIdentifier): T? {
        return operations[id]?.details as? T
    }

    inline fun <reified T> getResult(id: OperationIdentifier): T? {
        return results[id] as? T
    }

    companion object {
        private val LOGGER = Logging.getLogger(BuildOperationTracker::class.qualifiedName!!)
    }
}
