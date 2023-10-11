package org.nixos.gradle2nix

import io.kotest.assertions.fail
import io.kotest.common.ExperimentalKotest
import io.kotest.common.KotestInternal
import io.kotest.core.names.TestName
import io.kotest.core.source.sourceRef
import io.kotest.core.spec.style.scopes.ContainerScope
import io.kotest.core.spec.style.scopes.RootScope
import io.kotest.core.test.NestedTest
import io.kotest.core.test.TestScope
import io.kotest.core.test.TestType
import io.kotest.extensions.system.withEnvironment
import io.kotest.matchers.equals.beEqual
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.file.shouldBeAFile
import io.kotest.matchers.paths.shouldBeAFile
import io.kotest.matchers.should
import java.io.File
import java.io.FileFilter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.io.path.inputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okio.use

private val app = Gradle2Nix()

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

val testLogger = Logger(verbose = true, stacktrace = true)

fun fixture(path: String): File {
    return Paths.get("../fixtures", path).toFile()
}

@OptIn(ExperimentalKotest::class, ExperimentalSerializationApi::class, KotestInternal::class)
suspend fun TestScope.fixture(
    project: String,
    vararg args: String,
    test: suspend TestScope.(Map<String, Map<String, Artifact>>) -> Unit
) {
    val tmp = Paths.get("build/tmp/gradle2nix").apply { toFile().mkdirs() }
    val baseDir = Paths.get("../fixtures", project).toFile()
    val children = baseDir.listFiles(FileFilter { it.isDirectory && (it.name == "groovy" || it.name == "kotlin") })
        ?.toList()
    val cases = if (children.isNullOrEmpty()) {
        listOf(project to baseDir)
    } else {
        children.map { "$project.${it.name}" to it }
    }
    for (case in cases) {
        registerTestCase(
            NestedTest(
                name = TestName(case.first),
                disabled = false,
                config = null,
                type = TestType.Dynamic,
                source = sourceRef()
            ) {
                var dirName = case.second.toString().replace("/", ".")
                while (dirName.startsWith(".")) dirName = dirName.removePrefix(".")
                while (dirName.endsWith(".")) dirName = dirName.removeSuffix(".")

                val tempDir = File(tmp.toFile(), dirName)
                tempDir.deleteRecursively()
                case.second.copyRecursively(tempDir)

                if (!tempDir.resolve("settings.gradle").exists() && !tempDir.resolve("settings.gradle.kts").exists()) {
                    Files.createFile(tempDir.resolve("settings.gradle").toPath())
                }
                app.main(listOf("-d", tempDir.toString()) + args.withM2())
                val file = tempDir.resolve("${app.envFile}.json")
                file.shouldBeAFile()
                val env: Map<String, Map<String, Artifact>> = file.inputStream().buffered().use { input ->
                    Json.decodeFromStream(input)
                }
                test(env)
            }
        )
    }
}

val updateGolden = System.getProperty("org.nixos.gradle2nix.update-golden") != null

@OptIn(ExperimentalSerializationApi::class)
suspend fun TestScope.golden(
    project: String,
    vararg args: String,
) {
    fixture(project, *args) { env ->
        val filename = "${testCase.name.testName}.json"
        val goldenFile = File("../fixtures/golden/$filename")
        if (updateGolden) {
            goldenFile.parentFile.mkdirs()
            goldenFile.outputStream().buffered().use { output ->
                json.encodeToStream(env, output)
            }
        } else {
            if (!goldenFile.exists()) {
                fail("Golden file '$filename' doesn't exist. Run with --update-golden to generate.")
            }
            val goldenData: Map<String, Map<String, Artifact>> = try {
                goldenFile.inputStream().buffered().use { input ->
                    json.decodeFromStream(input)
                }
            } catch (e: SerializationException) {
                fail("Failed to load golden data from '$filename'. Run with --update-golden to regenerate.")
            }
            env should beEqual(goldenData)
        }
    }
}

val m2 = System.getProperty("org.nixos.gradle2nix.m2")

private fun Array<out String>.withM2(): List<String> {
    val args = toMutableList()
    if (args.indexOf("--") < 0) args.add("--")
    args.add("-Dorg.nixos.gradle2nix.m2=$m2")
    return args
}
