package org.nixos.gradle2nix

import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.nixos.gradle2nix.dependencygraph.DependencyGraphRenderer
import org.nixos.gradle2nix.dependencygraph.model.ResolvedConfiguration

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

class NixDependencyGraphRenderer : DependencyGraphRenderer {
    @OptIn(ExperimentalSerializationApi::class)
    override fun outputDependencyGraph(
        resolvedConfigurations: List<ResolvedConfiguration>,
        outputDirectory: File
    ) {
        val graphOutputFile = File(outputDirectory, "dependency-graph.json")
        graphOutputFile.outputStream().buffered().use { output ->
            json.encodeToStream(resolvedConfigurations, output)
        }
    }
}
