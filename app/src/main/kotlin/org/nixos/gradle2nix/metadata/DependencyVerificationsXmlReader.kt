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

import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.gradle.internal.UncheckedException
import org.nixos.gradle2nix.dependency.DefaultModuleComponentArtifactIdentifier
import org.nixos.gradle2nix.dependency.DefaultModuleComponentIdentifier
import org.nixos.gradle2nix.dependency.DefaultModuleIdentifier
import org.nixos.gradle2nix.dependency.ModuleComponentArtifactIdentifier
import org.nixos.gradle2nix.dependency.ModuleComponentIdentifier
import org.nixos.gradle2nix.dependency.ModuleIdentifier
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.ALSO_TRUST
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.ARTIFACT
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.COMPONENT
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.COMPONENTS
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.CONFIG
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.IGNORED_KEY
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.IGNORED_KEYS
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.KEY_SERVER
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.KEY_SERVERS
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.PGP
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.TRUST
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.TRUSTED_ARTIFACTS
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.TRUSTED_KEY
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.TRUSTED_KEYS
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.TRUSTING
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.VALUE
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.VERIFICATION_METADATA
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.VERIFY_METADATA
import org.nixos.gradle2nix.metadata.DependencyVerificationXmlTags.VERIFY_SIGNATURES
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

object DependencyVerificationsXmlReader {
    fun readFromXml(
        input: InputStream,
        builder: DependencyVerifierBuilder
    ) {
        try {
            val saxParser = createSecureParser()
            val xmlReader = saxParser.xmlReader
            val handler = VerifiersHandler(builder)
            xmlReader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            xmlReader.contentHandler = handler
            xmlReader.parse(InputSource(input))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Unable to read dependency verification metadata",
                e
            )
        } finally {
            try {
                input.close()
            } catch (e: IOException) {
                throw UncheckedException.throwAsUncheckedException(e)
            }
        }
    }

    fun readFromXml(input: InputStream): DependencyVerifier {
        val builder = DependencyVerifierBuilder()
        readFromXml(input, builder)
        return builder.build()
    }

    @Throws(ParserConfigurationException::class, SAXException::class)
    private fun createSecureParser(): SAXParser {
        val spf = SAXParserFactory.newInstance()
        spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        spf.setFeature("http://xml.org/sax/features/namespaces", false)
        spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return spf.newSAXParser()
    }

    private class VerifiersHandler(private val builder: DependencyVerifierBuilder) : DefaultHandler2() {
        private var inMetadata = false
        private var inComponents = false
        private var inConfiguration = false
        private var inVerifyMetadata = false
        private var inVerifySignatures = false
        private var inTrustedArtifacts = false
        private var inKeyServers = false
        private var inIgnoredKeys = false
        private var inTrustedKeys = false
        private var inTrustedKey = false
        private var currentTrustedKey: String? = null
        private var currentComponent: ModuleComponentIdentifier? = null
        private var currentArtifact: ModuleComponentArtifactIdentifier? =
            null
        private var currentChecksum: ChecksumKind? = null

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (qName) {
                CONFIG -> inConfiguration =
                    true

                VERIFICATION_METADATA -> inMetadata =
                    true

                COMPONENTS -> {
                    assertInMetadata()
                    inComponents = true
                }

                COMPONENT -> {
                    assertInComponents()
                    currentComponent = createComponentId(attributes)
                }

                ARTIFACT -> {
                    assertValidComponent()
                    currentArtifact = createArtifactId(attributes)
                }

                VERIFY_METADATA -> {
                    assertInConfiguration(VERIFY_METADATA)
                    inVerifyMetadata = true
                }

                VERIFY_SIGNATURES -> {
                    assertInConfiguration(VERIFY_SIGNATURES)
                    inVerifySignatures = true
                }

                TRUSTED_ARTIFACTS -> {
                    assertInConfiguration(TRUSTED_ARTIFACTS)
                    inTrustedArtifacts = true
                }

                TRUSTED_KEY -> {
                    assertContext(
                        inTrustedKeys,
                        TRUSTED_KEY,
                        TRUSTED_KEYS
                    )
                    inTrustedKey = true
                }

                TRUSTED_KEYS -> {
                    assertInConfiguration(TRUSTED_KEYS)
                    inTrustedKeys = true
                }

                TRUST -> {
                    assertInTrustedArtifacts()
                    addTrustedArtifact(attributes)
                }

                TRUSTING -> {
                    assertContext(
                        inTrustedKey,
                        TRUSTING,
                        TRUSTED_KEY
                    )
                }

                KEY_SERVERS -> {
                    assertInConfiguration(KEY_SERVERS)
                    inKeyServers = true
                }

                KEY_SERVER -> {
                    assertContext(
                        inKeyServers,
                        KEY_SERVER,
                        KEY_SERVERS
                    )
                }

                IGNORED_KEYS -> {
                    if (currentArtifact == null) {
                        assertInConfiguration(IGNORED_KEYS)
                    }
                    inIgnoredKeys = true
                }

                IGNORED_KEY -> {
                    assertContext(
                        inIgnoredKeys,
                        IGNORED_KEY,
                        IGNORED_KEYS
                    )
                }

                else -> if (currentChecksum != null && ALSO_TRUST == qName) {
                    builder.addChecksum(
                        currentArtifact!!,
                        currentChecksum!!,
                        getAttribute(attributes, VALUE),
                        null,
                        null
                    )
                } else if (currentArtifact != null) {
                    if (PGP != qName) {
                        currentChecksum = enumValueOf<ChecksumKind>(qName)
                        builder.addChecksum(
                            currentArtifact!!,
                            currentChecksum!!,
                            getAttribute(
                                attributes,
                                VALUE
                            ),
                            getNullableAttribute(
                                attributes,
                                DependencyVerificationXmlTags.ORIGIN
                            ),
                            getNullableAttribute(
                                attributes,
                                DependencyVerificationXmlTags.REASON
                            )
                        )
                    }
                }
            }
        }

        private fun assertInTrustedArtifacts() {
            assertContext(
                inTrustedArtifacts,
                TRUST,
                TRUSTED_ARTIFACTS
            )
        }

        private fun addTrustedArtifact(attributes: Attributes) {
            var regex = false
            val regexAttr = getNullableAttribute(
                attributes,
                DependencyVerificationXmlTags.REGEX
            )
            if (regexAttr != null) {
                regex = regexAttr.toBoolean()
            }
            builder.addTrustedArtifact(
                getNullableAttribute(
                    attributes,
                    DependencyVerificationXmlTags.GROUP
                ),
                getNullableAttribute(
                    attributes,
                    DependencyVerificationXmlTags.NAME
                ),
                getNullableAttribute(
                    attributes,
                    DependencyVerificationXmlTags.VERSION
                ),
                getNullableAttribute(
                    attributes,
                    DependencyVerificationXmlTags.FILE
                ),
                regex,
                getNullableAttribute(
                    attributes,
                    DependencyVerificationXmlTags.REASON
                )
            )
        }

        private fun readBoolean(ch: CharArray, start: Int, length: Int): Boolean {
            return String(ch, start, length).toBoolean()
        }

        private fun assertInConfiguration(tag: String) {
            assertContext(
                inConfiguration,
                tag,
                DependencyVerificationXmlTags.CONFIG
            )
        }

        private fun assertInComponents() {
            assertContext(
                inComponents,
                DependencyVerificationXmlTags.COMPONENT,
                DependencyVerificationXmlTags.COMPONENTS
            )
        }

        private fun assertInMetadata() {
            assertContext(
                inMetadata,
                DependencyVerificationXmlTags.COMPONENTS,
                DependencyVerificationXmlTags.VERIFICATION_METADATA
            )
        }

        private fun assertValidComponent() {
            assertContext(
                currentComponent != null,
                ARTIFACT,
                DependencyVerificationXmlTags.COMPONENT
            )
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (qName) {
                DependencyVerificationXmlTags.CONFIG -> inConfiguration =
                    false

                VERIFY_METADATA -> inVerifyMetadata =
                    false

                VERIFY_SIGNATURES -> inVerifySignatures =
                    false

                DependencyVerificationXmlTags.VERIFICATION_METADATA -> inMetadata =
                    false

                DependencyVerificationXmlTags.COMPONENTS -> inComponents =
                    false

                DependencyVerificationXmlTags.COMPONENT -> currentComponent =
                    null

                TRUSTED_ARTIFACTS -> inTrustedArtifacts =
                    false

                TRUSTED_KEYS -> inTrustedKeys =
                    false

                TRUSTED_KEY -> {
                    inTrustedKey = false
                    currentTrustedKey = null
                }

                KEY_SERVERS -> inKeyServers =
                    false

                ARTIFACT -> {
                    currentArtifact = null
                    currentChecksum = null
                }

                IGNORED_KEYS -> inIgnoredKeys =
                    false
            }
        }

        private fun createArtifactId(attributes: Attributes): ModuleComponentArtifactIdentifier {
            return DefaultModuleComponentArtifactIdentifier(
                currentComponent!!,
                getAttribute(
                    attributes,
                    DependencyVerificationXmlTags.NAME
                )
            )
        }

        private fun createComponentId(attributes: Attributes): ModuleComponentIdentifier {
            return DefaultModuleComponentIdentifier(
                createModuleId(attributes),
                getAttribute(
                    attributes,
                    DependencyVerificationXmlTags.VERSION
                )
            )
        }

        private fun createModuleId(attributes: Attributes): ModuleIdentifier {
            return DefaultModuleIdentifier(
                getAttribute(
                    attributes,
                    DependencyVerificationXmlTags.GROUP
                ),
                getAttribute(
                    attributes,
                    DependencyVerificationXmlTags.NAME
                )
            )
        }

        private fun getAttribute(attributes: Attributes, name: String): String {
            val value = attributes.getValue(name)
            assertContext(
                value != null,
                "Missing attribute: $name"
            )
            return value.intern()
        }

        private fun getNullableAttribute(attributes: Attributes, name: String): String? {
            val value = attributes.getValue(name) ?: return null
            return value.intern()
        }

        companion object {
            private fun assertContext(test: Boolean, innerTag: String, outerTag: String) {
                assertContext(
                    test,
                    "<$innerTag> must be found under the <$outerTag> tag"
                )
            }

            private fun assertContext(test: Boolean, message: String) {
                if (!test) {
                    throw IllegalStateException("Invalid dependency verification metadata file: $message")
                }
            }
        }
    }
}
