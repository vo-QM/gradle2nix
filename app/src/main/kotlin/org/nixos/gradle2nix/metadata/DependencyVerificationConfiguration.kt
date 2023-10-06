/*
 * Copyright 2019 the original author or authors.
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
package org.nixos.gradle2nix.metadata

import org.nixos.gradle2nix.dependency.ModuleComponentArtifactIdentifier
import org.nixos.gradle2nix.dependency.ModuleComponentIdentifier

class DependencyVerificationConfiguration(
    val trustedArtifacts: List<TrustCoordinates> = emptyList(),
) {
    data class TrustCoordinates internal constructor(
        val group: String?,
        val name: String?,
        val version: String?,
        val fileName: String?,
        val isRegex: Boolean,
        val reason: String?
    ) : Comparable<TrustCoordinates> {

        fun matches(id: ModuleComponentArtifactIdentifier): Boolean {
            val moduleComponentIdentifier: ModuleComponentIdentifier = id.componentIdentifier
            return (matches(group, moduleComponentIdentifier.group)
                    && matches(name, moduleComponentIdentifier.module)
                    && matches(version, moduleComponentIdentifier.version)
                    && matches(fileName, id.fileName))
        }

        private fun matches(value: String?, expr: String): Boolean {
            if (value == null) {
                return true
            }
            return if (!isRegex) {
                expr == value
            } else expr.matches(value.toRegex())
        }

        override fun compareTo(other: TrustCoordinates): Int {
            val regexComparison = isRegex.compareTo(other.isRegex)
            if (regexComparison != 0) {
                return regexComparison
            }
            val groupComparison = compareNullableStrings(
                group, other.group
            )
            if (groupComparison != 0) {
                return groupComparison
            }
            val nameComparison = compareNullableStrings(
                name, other.name
            )
            if (nameComparison != 0) {
                return nameComparison
            }
            val versionComparison = compareNullableStrings(
                version, other.version
            )
            if (versionComparison != 0) {
                return versionComparison
            }
            val fileNameComparison = compareNullableStrings(
                fileName, other.fileName
            )
            return if (fileNameComparison != 0) {
                fileNameComparison
            } else compareNullableStrings(
                reason, other.reason
            )
        }
    }

    companion object {
        private fun compareNullableStrings(first: String?, second: String?): Int {
            if (first == null) {
                return if (second == null) {
                    0
                } else {
                    -1
                }
            } else if (second == null) {
                return 1
            }
            return first.compareTo(second)
        }
    }
}
