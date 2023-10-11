package org.nixos.gradle2nix

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNot
import kotlinx.serialization.decodeFromString
import org.nixos.gradle2nix.metadata.Artifact
import org.nixos.gradle2nix.metadata.XmlFormat
import org.nixos.gradle2nix.metadata.parseVerificationMetadata

class VerificationMetadataTest : FunSpec({
    test("parses verification metadata") {
        val metadata = parseVerificationMetadata(testLogger, fixture("metadata/verification-metadata.xml"))
        metadata shouldNot beNull()
    }
})
