package org.nixos.gradle2nix.model

import java.io.Serializable
import org.nixos.gradle2nix.model.impl.DefaultRepository

interface DependencySet : Serializable {
    val dependencies: List<ResolvedDependency>
}
