package org.nixos.gradle2nix.dependencygraph.extractor

import java.io.File
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.GradleException
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.query.ArtifactResolutionQuery
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.component.Artifact
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.DefaultProjectComponentIdentifier
import org.gradle.api.internal.artifacts.configurations.ResolveConfigurationDependenciesBuildOperationType
import org.gradle.api.internal.artifacts.repositories.resolver.MavenUniqueSnapshotComponentIdentifier
import org.gradle.api.logging.Logging
import org.gradle.configuration.ApplyScriptPluginBuildOperationType
import org.gradle.configuration.ConfigurationTargetIdentifier
import org.gradle.initialization.LoadBuildBuildOperationType
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.exceptions.DefaultMultiCauseException
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationListener
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationProgressEvent
import org.gradle.internal.operations.OperationStartEvent
import org.gradle.ivy.IvyDescriptorArtifact
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.gradle.maven.MavenPomArtifact
import org.gradle.util.GradleVersion
import org.nixos.gradle2nix.dependencygraph.DependencyGraphRenderer
import org.nixos.gradle2nix.dependencygraph.util.BuildOperationTracker
import org.nixos.gradle2nix.dependencygraph.util.loadOptionalParam
import org.nixos.gradle2nix.model.ConfigurationTarget
import org.nixos.gradle2nix.model.DependencyCoordinates
import org.nixos.gradle2nix.model.DependencySource
import org.nixos.gradle2nix.model.PARAM_INCLUDE_CONFIGURATIONS
import org.nixos.gradle2nix.model.PARAM_INCLUDE_PROJECTS
import org.nixos.gradle2nix.model.PARAM_REPORT_DIR
import org.nixos.gradle2nix.model.Repository
import org.nixos.gradle2nix.model.ResolvedArtifact
import org.nixos.gradle2nix.model.ResolvedConfiguration
import org.nixos.gradle2nix.model.ResolvedDependency

abstract class DependencyExtractor :
    BuildOperationListener,
    AutoCloseable {

    private val configurations =
        ConcurrentHashMap<
                OperationIdentifier,
                Pair<ResolveConfigurationDependenciesBuildOperationType.Details,
                        ResolveConfigurationDependenciesBuildOperationType.Result>>()

    private val resolvedConfigurations = Collections.synchronizedList(mutableListOf<ResolvedConfiguration>())

    private val thrownExceptions = Collections.synchronizedList(mutableListOf<Throwable>())

    var rootProjectBuildDirectory: File? = null

    private val operationTracker = BuildOperationTracker()

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

    override fun started(buildOperation: BuildOperationDescriptor, startEvent: OperationStartEvent) {}

    override fun progress(operationIdentifier: OperationIdentifier, progressEvent: OperationProgressEvent) {}

    override fun finished(buildOperation: BuildOperationDescriptor, finishEvent: OperationFinishEvent) {
        operationTracker.finished(buildOperation, finishEvent)

        handleFinishBuildOperationType<
            ResolveConfigurationDependenciesBuildOperationType.Details,
            ResolveConfigurationDependenciesBuildOperationType.Result
                >(buildOperation, finishEvent) { details, result ->
            buildOperation.id?.let { operationId ->
                configurations[operationId] = details to result
            }
        }
    }

    private inline fun <reified D, reified R> handleFinishBuildOperationType(
        buildOperation: BuildOperationDescriptor,
        finishEvent: OperationFinishEvent,
        handler: (details: D, result: R) -> Unit
    ) {
        try {
            handleFinishBuildOperationTypeRaw<D, R>(buildOperation, finishEvent, handler)
        } catch (e: Throwable) {
            thrownExceptions.add(e)
            throw e
        }
    }

    private inline fun <reified D, reified R> handleFinishBuildOperationTypeRaw(
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

    // This returns null for the root build, because the build operation won't complete until after close() is called.
    private fun findBuildDetails(buildOperationId: OperationIdentifier?): LoadBuildBuildOperationType.Details? {
        return operationTracker.findParent(buildOperationId) {
            it.details as? LoadBuildBuildOperationType.Details
        }
    }

    private fun processConfigurations() {
        for ((operationId, data) in configurations) {
            val (details, result) = data
            extractConfigurationDependencies(operationId, details, result)
        }
    }

    private fun extractConfigurationDependencies(
        operationId: OperationIdentifier,
        details: ResolveConfigurationDependenciesBuildOperationType.Details,
        result: ResolveConfigurationDependenciesBuildOperationType.Result
    ) {
        val repositories = details.repositories?.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            (Repository(
                id = it.id,
                type = enumValueOf(it.type),
                name = it.name,
                m2Compatible = it.type == "MAVEN" || (it.properties["M2_COMPATIBLE"] as? Boolean) ?: false,
                metadataSources = (it.properties["METADATA_SOURCES"] as? List<String>) ?: emptyList(),
                metadataResources = metadataResources(it),
                artifactResources = artifactResources(it),
            ))
        } ?: emptyList()

        if (repositories.isEmpty()) {
            return
        }

        val rootComponent = result.rootComponent

        if (rootComponent.dependencies.isEmpty()) {
            // No dependencies to extract: can safely ignore
            return
        }

        val source: DependencySource = when {
            details.isScriptConfiguration -> {
                val parent = operationTracker.findParent(operationId) {
                    it.details as? ApplyScriptPluginBuildOperationType.Details
                } ?: throw IllegalStateException("Couldn't find parent script operation for ${details.configurationName}")
                DependencySource(
                    targetType = when (parent.targetType) {
                        ConfigurationTargetIdentifier.Type.GRADLE.label -> ConfigurationTarget.GRADLE
                        ConfigurationTargetIdentifier.Type.SETTINGS.label -> ConfigurationTarget.SETTINGS
                        ConfigurationTargetIdentifier.Type.PROJECT.label -> ConfigurationTarget.BUILDSCRIPT
                        else -> throw IllegalStateException("Unknown configuration target type: ${parent.targetType}")
                    },
                    targetPath = parent.targetPath ?: ":",
                    buildPath = parent.buildPath!!
                )
            }
            else -> {
                DependencySource(
                    targetType = ConfigurationTarget.PROJECT,
                    targetPath = details.projectPath!!,
                    buildPath = details.buildPath
                )
            }
        }


        val resolvedConfiguration = ResolvedConfiguration(source, details.configurationName, repositories)

        for (directDependency in getResolvedDependencies(rootComponent)) {
            val moduleComponentId = directDependency.id as? ModuleComponentIdentifier ?: continue
            val directDep = createComponentNode(
                moduleComponentId,
                source,
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
            if (!resolvedConfiguration.hasDependency(componentId(dependencyComponent))) {
                val moduleComponentId = dependencyComponent.id as? ModuleComponentIdentifier
                if (moduleComponentId != null) {
                    val dependencyNode = createComponentNode(
                        moduleComponentId,
                        componentSource,
                        direct,
                        dependencyComponent,
                        result.getRepositoryId(component)
                    )
                    resolvedConfiguration.addDependency(dependencyNode)
                }

                walkComponentDependencies(result, dependencyComponent, componentSource, resolvedConfiguration)
            }
        }
    }

    private fun getSource(component: ResolvedComponentResult, source: DependencySource): DependencySource {
        val componentId = component.id
        if (componentId is DefaultProjectComponentIdentifier) {
            return DependencySource(
                ConfigurationTarget.PROJECT,
                componentId.projectPath,
                componentId.build.buildPathCompat
            )
        }
        return source
    }

    private val BuildIdentifier.buildPathCompat: String
        @Suppress("DEPRECATION")
        get() = if (GradleVersion.current() < GradleVersion.version("8.2")) name else buildPath

    private fun getResolvedDependencies(component: ResolvedComponentResult): List<ResolvedComponentResult> {
        return component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }.filter { it != component }
    }

    private fun createComponentNode(
        componentId: ModuleComponentIdentifier,
        source: DependencySource,
        direct: Boolean,
        component: ResolvedComponentResult,
        repositoryId: String?
    ): ResolvedDependency {
        val componentDependencies =
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { componentId(it.selected) }
        val coordinates = coordinates(componentId)
        return ResolvedDependency(
            componentId.displayName,
            source,
            direct,
            coordinates,
            repositoryId,
            componentDependencies,
        )
    }

    private fun componentId(component: ResolvedComponentResult): String {
        return component.id.displayName
    }

    private fun coordinates(componentId: ModuleComponentIdentifier): DependencyCoordinates {
        return DependencyCoordinates(
            componentId.group,
            componentId.module,
            componentId.version,
            (componentId as? MavenUniqueSnapshotComponentIdentifier)?.timestamp
        )
    }

    private fun artifactType(type: Class<out Artifact>): ResolvedArtifact.Type? {
        return when (type) {
            SourcesArtifact::class.java -> ResolvedArtifact.Type.SOURCES
            JavadocArtifact::class.java -> ResolvedArtifact.Type.JAVADOC
            IvyDescriptorArtifact::class.java -> ResolvedArtifact.Type.IVY_DESCRIPTOR
            MavenPomArtifact::class.java -> ResolvedArtifact.Type.MAVEN_POM
            else -> null
        }
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
        LOGGER.lifecycle("DependencyExtractor: CLOSE")

        if (thrownExceptions.isNotEmpty()) {
            throw DefaultMultiCauseException(
                "The Gradle2Nix plugin encountered errors while extracting dependencies. " +
                    "Please report this issue at: https://github.com/tadfisher/gradle2nix/issues",
                thrownExceptions
            )
        }
        try {
            processConfigurations()

            LOGGER.lifecycle("Resolved ${resolvedConfigurations.size} configurations.")

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
