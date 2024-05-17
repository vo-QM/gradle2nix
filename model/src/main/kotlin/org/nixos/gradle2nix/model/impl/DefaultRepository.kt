package org.nixos.gradle2nix.model.impl

import org.nixos.gradle2nix.model.Repository

data class DefaultRepository(
    override val id: String,
    override val type: Repository.Type,
    override val metadataSources: List<String>,
    override val metadataResources: List<String>,
    override val artifactResources: List<String>,
) : Repository
