package org.nixos.gradle2nix.dependencygraph

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.nixos.gradle2nix.model.Repository
import org.nixos.gradle2nix.model.impl.DefaultDependencyCoordinates
import org.nixos.gradle2nix.model.impl.DefaultRepository

class DependencyUrlParserTest : FunSpec({
    val mavenCentral = DefaultRepository(
        "MavenRepo",
        Repository.Type.MAVEN,
        metadataSources = listOf("mavenPom"),
        metadataResources = listOf("https://repo.maven.apache.org/maven2/${DependencyExtractor.M2_PATTERN}"),
        artifactResources = listOf("https://repo.maven.apache.org/maven2/${DependencyExtractor.M2_PATTERN}")
    )

    test("parses maven url") {
        val url = "https://repo.maven.apache.org/maven2/com/github/ajalt/clikt-metadata/2.8.0/clikt-metadata-2.8.0.jar"
        val (coords, pattern) = parseComponent(listOf(mavenCentral), url).shouldNotBeNull()
        coords shouldBe DefaultDependencyCoordinates("com.github.ajalt", "clikt-metadata", "2.8.0")
        parseArtifact(pattern, coords, url) shouldBe "clikt-metadata-2.8.0.jar"
    }

    test("parses maven snapshot url") {
        val url = "https://repo.maven.apache.org/maven2/org/apache/test-SNAPSHOT2/2.0.2-SNAPSHOT/test-SNAPSHOT2-2.0.2-SNAPSHOT.jar"
        val (coords, pattern) = parseComponent(listOf(mavenCentral), url).shouldNotBeNull()
        coords shouldBe DefaultDependencyCoordinates("org.apache", "test-SNAPSHOT2", "2.0.2-SNAPSHOT")
        parseArtifact(pattern, coords, url) shouldBe "test-SNAPSHOT2-2.0.2-SNAPSHOT.jar"
    }

    test("parses maven timestamped snapshot url") {
        val url = "https://repo.maven.apache.org/maven2/org/apache/test-SNAPSHOT1/2.0.2-SNAPSHOT/test-SNAPSHOT1-2.0.2-20070310.181613-3.jar"
        val (coords, pattern) = parseComponent(listOf(mavenCentral), url).shouldNotBeNull()
        coords shouldBe DefaultDependencyCoordinates("org.apache", "test-SNAPSHOT1", "2.0.2-SNAPSHOT")
        parseArtifact(pattern, coords, url) shouldBe "test-SNAPSHOT1-2.0.2-SNAPSHOT.jar"
    }

    test("parses ivy descriptor url") {
        val url = "https://asset.opendof.org/ivy2/org.opendof.core-java/dof-cipher-sms4/1.0/ivy.xml"
        val (coords, pattern) = parseComponent(
            listOf(
                DefaultRepository(
                    "ivy",
                    Repository.Type.IVY,
                    metadataSources = listOf("ivyDescriptor"),
                    metadataResources = listOf("https://asset.opendof.org/ivy2/[organisation]/[module]/[revision]/ivy(.[platform]).xml"),
                    artifactResources = listOf("https://asset.opendof.org/artifact/[organisation]/[module]/[revision](/[platform])(/[type]s)/[artifact]-[revision](-[classifier]).[ext]")
                )
            ),
            url
        ).shouldNotBeNull()

        coords shouldBe DefaultDependencyCoordinates("org.opendof.core-java", "dof-cipher-sms4", "1.0")
        parseArtifact(pattern, coords, url) shouldBe "ivy-1.0.xml"
    }
})
