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
 * An identifier for a component instance which is available as a module version.
  */
interface ModuleComponentIdentifier : ComponentIdentifier {
    /**
     * The module group of the component.
     *
     * @return Component group
     */
    val group: String

    /**
     * The module name of the component.
     *
     * @return Component module
     */
    val module: String

    /**
     * The module version of the component.
     *
     * @return Component version
     */
    val version: String

    /**
     * The module identifier of the component. Returns the same information
     * as [group] and [module].
     *
     * @return the module identifier
     */
    val moduleIdentifier: ModuleIdentifier
}

data class DefaultModuleComponentIdentifier(
    override val moduleIdentifier: ModuleIdentifier,
    override val version: String,
) : ModuleComponentIdentifier {
    override val group: String
        get() = moduleIdentifier.group

    override val module: String
        get() = moduleIdentifier.name

    override val displayName: String
        get() = "$group:$module:$version"

    override fun toString(): String = displayName
}


fun ModuleComponentIdentifier(
    group: String,
    module: String,
    version: String
): ModuleComponentIdentifier = DefaultModuleComponentIdentifier(
    DefaultModuleIdentifier(group, module),
    version
)
