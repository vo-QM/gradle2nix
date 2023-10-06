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

enum class ChecksumKind(val algorithm: String) {
    md5("MD5"),
    sha1("SHA1"),
    sha256("SHA-256"),
    sha512("SHA-512");

    companion object {
        private val SORTED_BY_SECURITY: List<ChecksumKind> = listOf(sha512, sha256, sha1, md5)
        fun mostSecureFirst(): List<ChecksumKind> {
            return SORTED_BY_SECURITY
        }
    }
}
