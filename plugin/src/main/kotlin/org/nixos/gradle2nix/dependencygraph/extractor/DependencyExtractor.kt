package org.nixos.gradle2nix.dependencygraph.extractor

import java.io.File
import java.net.URI
import java.util.Collections
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.DefaultProjectComponentIdentifier
import org.gradle.api.internal.artifacts.configurations.ResolveConfigurationDependenciesBuildOperationType
import org.gradle.api.logging.Logging
import org.gradle.internal.exceptions.DefaultMultiCauseException
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationListener
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationProgressEvent
import org.gradle.internal.operations.OperationStartEvent
import org.nixos.gradle2nix.PARAM_INCLUDE_CONFIGURATIONS
import org.nixos.gradle2nix.PARAM_INCLUDE_PROJECTS
import org.nixos.gradle2nix.PARAM_REPORT_DIR
import org.nixos.gradle2nix.dependencygraph.DependencyGraphRenderer
import org.nixos.gradle2nix.dependencygraph.model.DependencyCoordinates
import org.nixos.gradle2nix.dependencygraph.model.DependencySource
import org.nixos.gradle2nix.dependencygraph.model.Repository
import org.nixos.gradle2nix.dependencygraph.model.ResolvedConfiguration
import org.nixos.gradle2nix.dependencygraph.model.ResolvedDependency
import org.nixos.gradle2nix.dependencygraph.util.loadOptionalParam

abstract class DependencyExtractor :
    BuildOperationListener,
    AutoCloseable {

    private val resolvedConfigurations = Collections.synchronizedList(mutableListOf<ResolvedConfiguration>())

    private val thrownExceptions = Collections.synchronizedList(mutableListOf<Throwable>())

    var rootProjectBuildDirectory: File? = null

    // Properties are lazily initialized so that System Properties are initialized by the time
    // the values are used. This is required due to a bug in older Gradle versions. (https://github.com/gradle/gradle/issues/6825)
    private val configurationFilter by lazy {
        ResolvedConfigurationFilter(
            loadOptionalParam(PARAM_INCLUDE_PROJECTS),
            loadOptionalParam(PARAM_INCLUDE_CONFIGURATIONS)
        )
    }

    private val dependencyGraphReportDir by lazy {
        loadOptionalParam(PARAM_REPORT_DIR)
    }

    abstract fun getRendererClassName(): String

    override fun started(buildOperation: BuildOperationDescriptor, startEvent: OperationStartEvent) {
        // This method will never be called when registered in a `BuildServiceRegistry` (ie. Gradle 6.1 & higher)
        // No-op
    }

    override fun progress(operationIdentifier: OperationIdentifier, progressEvent: OperationProgressEvent) {
        // This method will never be called when registered in a `BuildServiceRegistry` (ie. Gradle 6.1 & higher)
        // No-op
    }

    override fun finished(buildOperation: BuildOperationDescriptor, finishEvent: OperationFinishEvent) {
        handleBuildOperationType<
            ResolveConfigurationDependenciesBuildOperationType.Details,
            ResolveConfigurationDependenciesBuildOperationType.Result
                >(buildOperation, finishEvent) { details, result -> extractConfigurationDependencies(details, result) }
    }

    private inline fun <reified D, reified R> handleBuildOperationType(
        buildOperation: BuildOperationDescriptor,
        finishEvent: OperationFinishEvent,
        handler: (details: D, result: R) -> Unit
    ) {
        try {
            handleBuildOperationTypeRaw<D, R>(buildOperation, finishEvent, handler)
        } catch (e: Throwable) {
            thrownExceptions.add(e)
            throw e
        }
    }

    private inline fun <reified D, reified R> handleBuildOperationTypeRaw(
        buildOperation: BuildOperationDescriptor,
        finishEvent: OperationFinishEvent,
        handler: (details: D, result: R) -> Unit
    ) {
        val details: D? = buildOperation.details.let {
            if (it is D) it else null
        }
        val result: R? = finishEvent.result.let {
            if (it is R) it else null
        }
        if (details == null && result == null) {
            return
        } else if (details == null || result == null) {
            throw IllegalStateException("buildOperation.details & finishedEvent.result were unexpected types")
        }
        handler(details, result)
    }

    private fun extractConfigurationDependencies(
        details: ResolveConfigurationDependenciesBuildOperationType.Details,
        result: ResolveConfigurationDependenciesBuildOperationType.Result
    ) {
        val repositories = details.repositories?.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            Repository(
                id = it.id,
                type = enumValueOf(it.type),
                name = it.name,
                m2Compatible = it.type == "MAVEN" || (it.properties["M2_COMPATIBLE"] as? Boolean) ?: false,
                metadataSources = (it.properties["METADATA_SOURCES"] as? List<String>) ?: emptyList(),
                metadataResources = metadataResources(it),
                artifactResources = artifactResources(it),
            )
        } ?: emptyList()
        val rootComponent = result.rootComponent

        if (rootComponent.dependencies.isEmpty()) {
            // No dependencies to extract: can safely ignore
            return
        }
        val projectIdentityPath = (rootComponent.id as? DefaultProjectComponentIdentifier)?.identityPath?.path

        // TODO: At this point, any resolution not bound to a particular project will be assigned to the root "build :"
        // This is because `details.buildPath` is always ':', which isn't correct in a composite build.
        // It is possible to do better. By tracking the current build operation context, we can assign more precisely.
        // See the Gradle Enterprise Build Scan Plugin: `ConfigurationResolutionCapturer_5_0`
        val rootPath = projectIdentityPath ?: details.buildPath

        if (!configurationFilter.include(rootPath, details.configurationName)) {
            LOGGER.debug("Ignoring resolved configuration: $rootPath - ${details.configurationName}")
            return
        }

        val rootId = if (projectIdentityPath == null) "build $rootPath" else componentId(rootComponent)
        val rootSource = DependencySource(rootId, rootPath)
        val resolvedConfiguration = ResolvedConfiguration(rootSource, details.configurationName, repositories)

        for (directDependency in getResolvedDependencies(rootComponent)) {
            val directDep = createComponentNode(
                componentId(directDependency),
                rootSource,
                true,
                directDependency,
                result.getRepositoryId(directDependency)
            )
            resolvedConfiguration.addDependency(directDep)

            walkComponentDependencies(result, directDependency, directDep.source, resolvedConfiguration)
        }

        resolvedConfigurations.add(resolvedConfiguration)
    }

    private fun walkComponentDependencies(
        result: ResolveConfigurationDependenciesBuildOperationType.Result,
        component: ResolvedComponentResult,
        parentSource: DependencySource,
        resolvedConfiguration: ResolvedConfiguration
    ) {
        val componentSource = getSource(component, parentSource)
        val direct = componentSource != parentSource

        val dependencyComponents = getResolvedDependencies(component)
        for (dependencyComponent in dependencyComponents) {
            val dependencyId = componentId(dependencyComponent)
            if (!resolvedConfiguration.hasDependency(dependencyId)) {
                val dependencyNode = createComponentNode(
                    dependencyId,
                    componentSource,
                    direct,
                    dependencyComponent,
                    result.getRepositoryId(component)
                )
                resolvedConfiguration.addDependency(dependencyNode)

                walkComponentDependencies(result, dependencyComponent, componentSource, resolvedConfiguration)
            }
        }
    }

    private fun getSource(component: ResolvedComponentResult, source: DependencySource): DependencySource {
        val componentId = component.id
        if (componentId is DefaultProjectComponentIdentifier) {
            return DependencySource(componentId(component), componentId.identityPath.path)
        }
        return source
    }

    private fun getResolvedDependencies(component: ResolvedComponentResult): List<ResolvedComponentResult> {
        return component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }.filter { it != component }
    }

    private fun createComponentNode(componentId: String, source: DependencySource, direct: Boolean, component: ResolvedComponentResult, repositoryId: String?): ResolvedDependency {
        val componentDependencies = component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { componentId(it.selected) }
        return ResolvedDependency(
            componentId,
            source,
            direct,
            coordinates(component),
            repositoryId,
            componentDependencies
        )
    }

    private fun componentId(component: ResolvedComponentResult): String {
        return component.id.displayName
    }

    private fun coordinates(component: ResolvedComponentResult): DependencyCoordinates {
        // TODO: Consider and handle null moduleVersion
        val moduleVersionIdentifier = component.moduleVersion!!
        return DependencyCoordinates(
            moduleVersionIdentifier.group,
            moduleVersionIdentifier.name,
            moduleVersionIdentifier.version
        )
    }

    private fun writeDependencyGraph() {
        val outputDirectory = getOutputDir()
        outputDirectory.mkdirs()
        createRenderer().outputDependencyGraph(resolvedConfigurations, outputDirectory)
        LOGGER.info("Wrote dependency graph to ${getOutputDir()}")
    }

    private fun createRenderer(): DependencyGraphRenderer {
        LOGGER.info("Constructing renderer: ${getRendererClassName()}")
        return Class.forName(getRendererClassName()).getDeclaredConstructor().newInstance() as DependencyGraphRenderer
    }

    private fun getOutputDir(): File {
        if (dependencyGraphReportDir != null) {
            return File(dependencyGraphReportDir!!)
        }

        if (rootProjectBuildDirectory == null) {
            throw RuntimeException("Cannot determine report file location")
        }
        return File(
            rootProjectBuildDirectory,
            "reports/nix-dependency-graph"
        )
    }

    override fun close() {
        if (thrownExceptions.isNotEmpty()) {
            throw DefaultMultiCauseException(
                "The Gradle2Nix plugin encountered errors while extracting dependencies. " +
                    "Please report this issue at: https://github.com/tadfisher/gradle2nix/issues",
                thrownExceptions
            )
        }
        try {
            writeDependencyGraph()
        } catch (e: RuntimeException) {
            throw GradleException(
                "The Gradle2Nix plugin encountered errors while writing the dependency snapshot json file. " +
                    "Please report this issue at: https://github.com/tadfisher/gradle2nix/issues",
                e
            )
        }
    }

    companion object {
        private val LOGGER = Logging.getLogger(DependencyExtractor::class.java)

        private const val M2_PATTERN =
            "[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])"

        private const val IVY_ARTIFACT_PATTERN = "[organisation]/[module]/[revision]/[type]s/[artifact](.[ext])";

        private fun resources(urls: List<URI>, patterns: List<String>): List<String> {
            if (urls.isEmpty()) {
                return patterns
            }
            if (patterns.isEmpty()) {
                return urls.map { it.toString() }
            }
            return mutableListOf<String>().apply {
                for (pattern in patterns) {
                    for (url in urls) {
                        add(
                            url.toString()
                                .removeSuffix("/")
                                .plus("/")
                                .plus(pattern.removePrefix("/"))
                        )
                    }
                }
            }
        }

        private fun metadataResources(
            repository: ResolveConfigurationDependenciesBuildOperationType.Repository
        ): List<String> {
            return when (repository.type) {
                Repository.Type.MAVEN.name -> {
                    resources(
                        listOfNotNull(repository.properties["URL"] as? URI),
                        listOf(M2_PATTERN)
                    )
                }
                Repository.Type.IVY.name -> {
                    @Suppress("UNCHECKED_CAST")
                    resources(
                        listOfNotNull(repository.properties["URL"] as? URI),
                        repository.properties["IVY_PATTERNS"] as? List<String> ?: listOf(IVY_ARTIFACT_PATTERN)
                    )
                }
                else -> emptyList()
            }
        }

        private fun artifactResources(
            repository: ResolveConfigurationDependenciesBuildOperationType.Repository
        ): List<String> {
            return when (repository.type) {
                Repository.Type.MAVEN.name -> {
                    @Suppress("UNCHECKED_CAST")
                    resources(
                        listOfNotNull(repository.properties["URL"] as? URI)
                            .plus(repository.properties["ARTIFACT_URLS"] as? List<URI> ?: emptyList()),
                        listOf(M2_PATTERN)
                    )
                }
                Repository.Type.IVY.name -> {
                    @Suppress("UNCHECKED_CAST")
                    resources(
                        listOfNotNull(repository.properties["URL"] as? URI),
                        repository.properties["ARTIFACT_PATTERNS"] as? List<String> ?: listOf(IVY_ARTIFACT_PATTERN)
                    )
                }
                else -> emptyList()
            }
        }
    }
}
