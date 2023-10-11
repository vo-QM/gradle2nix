package org.nixos.gradle2nix

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

data class Config(
    val appHome: File,
    val gradleHome: File,
    val gradleVersion: String?,
    val gradleJdk: File?,
    val gradleArgs: List<String>,
    val projectFilter: String?,
    val configurationFilter: String?,
    val projectDir: File,
    val tasks: List<String>,
    val logger: Logger,
)

@OptIn(ExperimentalSerializationApi::class)
val JsonFormat = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

class Gradle2Nix : CliktCommand(
    name = "gradle2nix"
) {
    private val gradleVersion: String? by option(
        "--gradle-version", "-g",
        metavar = "VERSION",
        help = "Use a specific Gradle version"
    )

    private val gradleJdk: File? by option(
        "--gradle-jdk", "-j",
        metavar = "DIR",
        help = "JDK home directory to use for launching Gradle (default: ${System.getProperty("java.home")})"
    ).file(canBeFile = false, canBeDir = true)

    private val projectFilter: String? by option(
        "--projects", "-p",
        metavar = "REGEX",
        help = "Regex to filter Gradle projects (default: include all projects)"
    )

    private val configurationFilter: String? by option(
        "--configurations", "-c",
        metavar = "REGEX",
        help = "Regex to filter Gradle configurations (default: include all configurations)")

    val outDir: File? by option(
        "--out-dir", "-o",
        metavar = "DIR",
        help = "Path to write generated files (default: PROJECT-DIR)")
        .file(canBeFile = false, canBeDir = true)

    val envFile: String by option(
        "--env", "-e",
        metavar = "FILENAME",
        help = "Prefix for environment files (.json and .nix)")
        .default("gradle-env")

    private val quiet: Boolean by option("--quiet", "-q", help = "Disable logging")
        .flag(default = false)

    private val projectDir: File by option(
        "--projectDir", "-d",
        metavar = "PROJECT-DIR",
        help = "Path to the project root (default: .)")
        .file()
        .default(File("."), "Current directory")
        .validate { file ->
            if (!file.exists()) fail("Directory \"$file\" does not exist.")
            if (file.isFile) fail("Directory \"$file\" is a file.")
            if (!file.canRead()) fail("Directory \"$file\" is not readable.")
            if (!file.isProjectRoot()) fail("Directory \"$file\" is not a Gradle project.")
        }

    private val tasks: List<String> by option(
        "--tasks", "-t",
        metavar = "TASKS",
        help = "Gradle tasks to run"
    ).multiple()

    private val gradleArgs: List<String> by argument(
        name = "ARGS",
        help = "Extra arguments to pass to Gradle"
    ).multiple()

    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun run() {
        val appHome = System.getProperty("org.nixos.gradle2nix.share")
        if (appHome == null) {
            System.err.println("Error: could not locate the /share directory in the gradle2nix installation")
        }
        val gradleHome = System.getenv("GRADLE_USER_HOME")?.let(::File) ?: File("${System.getProperty("user.home")}/.gradle")
        val logger = Logger(verbose = !quiet)

        val config = Config(
            File(appHome),
            gradleHome,
            gradleVersion,
            gradleJdk,
            gradleArgs,
            projectFilter,
            configurationFilter,
            projectDir,
            tasks,
            logger
        )

        val metadata = File("$projectDir/gradle/verification-metadata.xml")
        if (metadata.exists()) {
            val backup = metadata.resolveSibling("verification-metadata.xml.bak")
            if (metadata.renameTo(backup)) {
                Runtime.getRuntime().addShutdownHook(Thread {
                    metadata.delete()
                    backup.renameTo(metadata)
                })
            } else {
                metadata.deleteOnExit()
            }
        }

        connect(config).use { connection ->
            connection.build(config)
        }

        val dependencies = try {
            processDependencies(config)
        } catch (e: Throwable) {
            error("Dependency parsing failed: ${e.message}")
        }

        val outDir = outDir ?: projectDir
        val json = outDir.resolve("$envFile.json")
        logger.log("Writing environment to $json")
        json.outputStream().buffered().use { output ->
            JsonFormat.encodeToStream(dependencies, output)
        }
    }
}

fun main(args: Array<String>) {
    Gradle2Nix().main(args)
}
