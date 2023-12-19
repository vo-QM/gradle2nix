package org.nixos.gradle2nix.dependencygraph

import java.io.File
import org.nixos.gradle2nix.model.ResolvedConfiguration

interface DependencyGraphRenderer {
    fun outputDependencyGraph(
        resolvedConfigurations: List<ResolvedConfiguration>,
        outputDirectory: File
    )
}
