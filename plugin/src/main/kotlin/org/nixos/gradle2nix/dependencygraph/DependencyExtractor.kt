package org.nixos.gradle2nix.dependencygraph

import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.optionals.getOrNull
import org.gradle.api.internal.artifacts.DownloadArtifactBuildOperationType
import org.gradle.api.internal.artifacts.configurations.ResolveConfigurationDependenciesBuildOperationType
import org.gradle.api.logging.Logging
import org.gradle.internal.operations.BuildOperationAncestryTracker
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationListener
import org.gradle.internal.operations.OperationFinishEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.operations.OperationProgressEvent
import org.gradle.internal.operations.OperationStartEvent
import org.gradle.internal.resource.ExternalResourceReadMetadataBuildOperationType
import org.nixos.gradle2nix.model.DependencyCoordinates
import org.nixos.gradle2nix.model.DependencySet
import org.nixos.gradle2nix.model.Repository
import org.nixos.gradle2nix.model.impl.DefaultDependencyCoordinates
import org.nixos.gradle2nix.model.impl.DefaultDependencySet
import org.nixos.gradle2nix.model.impl.DefaultRepository
import org.nixos.gradle2nix.model.impl.DefaultResolvedArtifact
import org.nixos.gradle2nix.model.impl.DefaultResolvedDependency

class DependencyExtractor(
    private val ancestryTracker: BuildOperationAncestryTracker,
) : BuildOperationListener {

    // Repositories by ID
    private val repositories: MutableMap<String, DefaultRepository> = ConcurrentHashMap()

    private val thrownExceptions = Collections.synchronizedList(mutableListOf<Throwable>())

    private val artifacts: MutableMap<
            OperationIdentifier,
            DownloadArtifactBuildOperationType.Details
            > = ConcurrentHashMap()

    private val files: MutableMap<
            OperationIdentifier,
            ExternalResourceReadMetadataBuildOperationType.Details
            > = ConcurrentHashMap()

    private val fileArtifacts: MutableMap<OperationIdentifier, OperationIdentifier> = ConcurrentHashMap()

    fun buildDependencySet(): DependencySet {
        println("DependencyExtractor: buildDependencySet (wtf)")

        val repoList = repositories.values.toList()

        val dependencies = buildMap<DependencyCoordinates, MutableMap<String, MutableSet<Pair<String, MutableSet<String>>>>> {
            for ((fileId, file) in files) {
                val filename = file.location.substringAfterLast("/").substringBefore('#').substringBefore('?')
                if (filename == "maven-metadata.xml") {
                    // Skip Maven metadata, we don't need it for the local repository
                    continue
                }

                val artifactOperationId = fileArtifacts[fileId]
                val artifact = artifactOperationId?.let { artifacts[it] }
                val artifactIdentifier = artifact?.artifactIdentifier?.let(::parseArtifactIdentifier)
                var coords = artifactIdentifier?.first
                var name = artifactIdentifier?.second

                if (coords == null || name == null) {
                    val parsed = parseComponent(repoList, file.location)
                    if (parsed == null) {
                        LOGGER.info("Couldn't parse location for ${artifactIdentifier?.first?.toString() ?: name}: ${file.location}")
                        continue
                    }
                    coords = coords ?: parsed.first
                    name = name ?: parseArtifact(parsed.second, coords, file.location)
                }

                getOrPut(coords) { mutableMapOf() }
                    .getOrPut(name) { mutableSetOf() }
                    .run {
                        val existing = find { it.first == filename }
                        if (existing != null) {
                            existing.second.add(file.location)
                        } else {
                            add(filename to mutableSetOf(file.location))
                        }
                    }
            }
        }

        return DefaultDependencySet(
            dependencies = dependencies.map { (coords, artifacts) ->
                DefaultResolvedDependency(
                    coords,
                    artifacts.flatMap { (name, files) ->
                        files.map { (filename, urls) ->
                            DefaultResolvedArtifact(name, filename, urls.toList())
                        }
                    }
                )
            }
        )
    }

    override fun started(buildOperation: BuildOperationDescriptor, startEvent: OperationStartEvent) {
        val id = buildOperation.id ?: return

        when (val details = buildOperation.details) {
            is ResolveConfigurationDependenciesBuildOperationType.Details -> {
                for (repository in details.repositories.orEmpty()) {
                    addRepository(repository)
                }
            }

            is DownloadArtifactBuildOperationType.Details -> {
                artifacts[id] = details
            }

            is ExternalResourceReadMetadataBuildOperationType.Details -> {
                files[id] = details

                ancestryTracker.findClosestMatchingAncestor(id) { it in artifacts }.getOrNull()?.let {
                    fileArtifacts[id] = it
                }
            }
        }
    }

    override fun progress(operationIdentifier: OperationIdentifier, progressEvent: OperationProgressEvent) {}

    override fun finished(buildOperation: BuildOperationDescriptor, finishEvent: OperationFinishEvent) {}

    private fun addRepository(
        repository: ResolveConfigurationDependenciesBuildOperationType.Repository
    ): DefaultRepository {
        @Suppress("UNCHECKED_CAST")
        val candidate = DefaultRepository(
            id = repository.id,
            type = enumValueOf(repository.type),
            metadataSources = (repository.properties["METADATA_SOURCES"] as? List<String>) ?: emptyList(),
            metadataResources = metadataResources(repository),
            artifactResources = artifactResources(repository),
        )

        // Repository IDs are not unique across the entire build, unfortunately.
        val existing = repositories.values.find {
            it.type == candidate.type &&
                    it.metadataSources == candidate.metadataSources &&
                    it.metadataResources == candidate.metadataResources &&
                    it.artifactResources == candidate.artifactResources
        }

        if (existing != null) return existing
        var inc = 0
        fun incId() = if (inc > 0) "${candidate.id}[$inc]" else candidate.id
        while (incId() in repositories) inc++

        val added = if (inc > 0) candidate else candidate.copy(id = incId())
        repositories[added.id] = added
        return added
    }

    companion object {
        private val LOGGER = Logging.getLogger(DependencyExtractor::class.java)

        internal const val M2_PATTERN =
            "[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]"

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
                    val patterns = repository.properties["IVY_PATTERNS"] as? List<String>
                        ?: listOf(IVY_ARTIFACT_PATTERN)

                    resources(
                        listOfNotNull(repository.properties["URL"] as? URI),
                        patterns
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
                    (resources(
                        listOfNotNull(repository.properties["URL"] as? URI)
                            .plus(repository.properties["ARTIFACT_URLS"] as? List<URI> ?: emptyList()),
                        listOf(M2_PATTERN)
                    ))
                }
                Repository.Type.IVY.name -> {
                    @Suppress("UNCHECKED_CAST")
                    val patterns = repository.properties["ARTIFACT_PATTERNS"] as? List<String>
                        ?: listOf(IVY_ARTIFACT_PATTERN)

                    resources(
                        listOfNotNull(repository.properties["URL"] as? URI),
                        patterns
                    )
                }
                else -> emptyList()
            }
        }

        private val artifactRegex = Regex("(?<name>\\S+) \\((?<coordinates>\\S+)\\)")

        private fun parseArtifactIdentifier(input: String): Pair<DependencyCoordinates, String>? {
            val groups = artifactRegex.matchEntire(input)?.groups ?: return null.also {
                LOGGER.warn("artifact regex didn't match $input")
            }
            val coords = groups["coordinates"]?.value?.let(DefaultDependencyCoordinates::parse) ?: return null
            val name = groups["name"]?.value ?: return null
            return coords to name
        }
    }
}
