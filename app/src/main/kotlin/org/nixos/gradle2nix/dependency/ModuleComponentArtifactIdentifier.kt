/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nixos.gradle2nix.dependency

/**
 * An immutable identifier for an artifact that belongs to some module version.
 */
interface ModuleComponentArtifactIdentifier : ComponentArtifactIdentifier {
    /**
     * Returns the id of the component that this artifact belongs to.
     */
    override val componentIdentifier: ModuleComponentIdentifier

    /**
     * Returns a file base name that can be used for this artifact.
     */
    val fileName: String
}

data class DefaultModuleComponentArtifactIdentifier(
    override val componentIdentifier: ModuleComponentIdentifier,
    override val fileName: String,
) : ModuleComponentArtifactIdentifier {
    override val displayName: String get() = "$fileName ($componentIdentifier)"

    override fun toString(): String = displayName
}
