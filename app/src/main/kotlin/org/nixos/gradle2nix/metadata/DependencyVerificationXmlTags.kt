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

internal object DependencyVerificationXmlTags {
    const val ALSO_TRUST = "also-trust"
    const val ARTIFACT = "artifact"
    const val COMPONENT = "component"
    const val COMPONENTS = "components"
    const val CONFIG = "configuration"
    const val ENABLED = "enabled"
    const val FILE = "file"
    const val GROUP = "group"
    const val ID = "id"
    const val IGNORED_KEY = "ignored-key"
    const val IGNORED_KEYS = "ignored-keys"
    const val KEY_SERVER = "key-server"
    const val KEY_SERVERS = "key-servers"
    const val NAME = "name"
    const val ORIGIN = "origin"
    const val PGP = "pgp"
    const val REASON = "reason"
    const val REGEX = "regex"
    const val TRUST = "trust"
    const val TRUSTED_ARTIFACTS = "trusted-artifacts"
    const val TRUSTED_KEY = "trusted-key"
    const val TRUSTED_KEYS = "trusted-keys"
    const val TRUSTING = "trusting"
    const val URI = "uri"
    const val VALUE = "value"
    const val VERIFICATION_METADATA = "verification-metadata"
    const val VERIFY_METADATA = "verify-metadata"
    const val VERIFY_SIGNATURES = "verify-signatures"
    const val VERSION = "version"
}
