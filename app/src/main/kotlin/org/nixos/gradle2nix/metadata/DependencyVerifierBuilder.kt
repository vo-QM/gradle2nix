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

class DependencyVerifierBuilder {
    private val byComponent: MutableMap<ModuleComponentIdentifier, ComponentVerificationsBuilder> = mutableMapOf()
    private val trustedArtifacts: MutableList<DependencyVerificationConfiguration.TrustCoordinates> = mutableListOf()
    
    fun addChecksum(
        artifact: ModuleComponentArtifactIdentifier,
        kind: ChecksumKind,
        value: String,
        origin: String?,
        reason: String?
    ) {
        val componentIdentifier: ModuleComponentIdentifier = artifact.componentIdentifier
        byComponent.getOrPut(componentIdentifier) {
            ComponentVerificationsBuilder(componentIdentifier)
        }.addChecksum(artifact, kind, value, origin, reason)
    }

    @JvmOverloads
    fun addTrustedArtifact(
        group: String?,
        name: String?,
        version: String?,
        fileName: String?,
        regex: Boolean,
        reason: String? = null
    ) {
        validateUserInput(group, name, version, fileName)
        trustedArtifacts.add(DependencyVerificationConfiguration.TrustCoordinates(group, name, version, fileName, regex, reason))
    }

    private fun validateUserInput(
        group: String?,
        name: String?,
        version: String?,
        fileName: String?
    ) {
        // because this can be called from parsing XML, we need to perform additional verification
        if (group == null && name == null && version == null && fileName == null) {
            throw IllegalStateException("A trusted artifact must have at least one of group, name, version or file name not null")
        }
    }

    fun build(): DependencyVerifier {
        return DependencyVerifier(
            byComponent
                .toSortedMap(
                    compareBy<ModuleComponentIdentifier> { it.group }
                        .thenBy { it.module }
                        .thenBy { it.version }
                )
                .mapValues { it.value.build() },
            DependencyVerificationConfiguration(trustedArtifacts),
        )
    }

    private class ComponentVerificationsBuilder(private val component: ModuleComponentIdentifier) {
        private val byArtifact: MutableMap<String, ArtifactVerificationBuilder> = mutableMapOf()

        fun addChecksum(
            artifact: ModuleComponentArtifactIdentifier,
            kind: ChecksumKind,
            value: String,
            origin: String?,
            reason: String?
        ) {
            byArtifact.computeIfAbsent(artifact.fileName) { ArtifactVerificationBuilder() }
                .addChecksum(kind, value, origin, reason)
        }

        fun build(): ComponentVerificationMetadata {
            return ComponentVerificationMetadata(
                component,
                byArtifact
                    .map { ArtifactVerificationMetadata(it.key, it.value.buildChecksums()) }
                    .sortedBy { it.artifactName }
            )
        }
    }

    protected class ArtifactVerificationBuilder {
        private val builder: MutableMap<ChecksumKind, ChecksumBuilder> = mutableMapOf()

        fun addChecksum(kind: ChecksumKind, value: String, origin: String?, reason: String?) {
            val builder = builder.getOrPut(kind) {
                ChecksumBuilder(kind)
            }
            builder.addChecksum(value)
            if (origin != null) {
                builder.withOrigin(origin)
            }
            if (reason != null) {
                builder.withReason(reason)
            }
        }

        fun buildChecksums(): List<Checksum> {
            return builder.values
                .map(ChecksumBuilder::build)
                .sortedBy { it.kind }
        }
    }

    private class ChecksumBuilder(private val kind: ChecksumKind) {
        private var value: String? = null
        private var origin: String? = null
        private var reason: String? = null
        private var alternatives: MutableSet<String> = mutableSetOf()

        /**
         * Sets the origin, if not set already. This is
         * mostly used for automatic generation of checksums
         */
        fun withOrigin(origin: String?) {
            this.origin = this.origin ?: origin
        }

        /**
         * Sets the reason, if not set already.
         */
        fun withReason(reason: String?) {
            this.reason = this.reason ?: reason
        }

        fun addChecksum(checksum: String) {
            if (value == null) {
                value = checksum
            } else if (value != checksum) {
                alternatives.add(checksum)
            }
        }

        fun build(): Checksum {
            return Checksum(
                kind,
                checkNotNull(value) { "Checksum is null" },
                alternatives,
                origin,
                reason
            )
        }
    }
}
