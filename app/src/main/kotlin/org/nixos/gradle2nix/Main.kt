package org.nixos.gradle2nix

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.gradle.tooling.model.gradle.GradleBuild
import org.nixos.gradle2nix.model.DependencySet

data class Config(
    val appHome: File,
    val gradleHome: File,
    val gradleVersion: String?,
    val gradleJdk: File?,
    val gradleArgs: List<String>,
    val outDir: File,
    val projectDir: File,
    val tasks: List<String>,
    val logger: Logger,
    val dumpEvents: Boolean
)

@OptIn(ExperimentalSerializationApi::class)
val JsonFormat = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

enum class LogLevel {
    debug,
    info,
    warn,
    error,
}

class Gradle2Nix : CliktCommand(
    name = "gradle2nix"
) {
    private val tasks: List<String> by option(
        "--task", "-t",
        metavar = "TASK",
        help = "Gradle tasks to run"
    ).multiple()

    private val projectDir: File by option(
        "--project", "-p",
        help = "Path to the project root")
        .file()
        .default(File("."), "Current directory")
        .validate { file ->
            if (!file.exists()) fail("Directory \"$file\" does not exist.")
            if (file.isFile) fail("Directory \"$file\" is a file.")
            if (!file.canRead()) fail("Directory \"$file\" is not readable.")
            if (!file.isProjectRoot()) fail("Directory \"$file\" is not a Gradle project.")
        }

    private val outDir: File? by option(
        "--out-dir", "-o",
        metavar = "DIR",
        help = "Path to write generated files")
        .file(canBeFile = false, canBeDir = true)
        .defaultLazy("<project>") { projectDir }

    internal val lockFile: String by option(
        "--lock-file", "-l",
        metavar = "FILENAME",
        help = "Name of the generated lock file"
    ).default("gradle.lock")

    private val nixFile: String by option(
        "--nix-file", "-n",
        metavar = "FILENAME",
        help = "Name of the generated Nix file"
    ).default("gradle.nix")

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

    private val logLevel: LogLevel by option(
        "--log",
        help = "Print messages with this priority or higher")
        .enum<LogLevel>()
        .default(LogLevel.error)

    private val dumpEvents: Boolean by option(
        "--dump-events",
        help = "Dump Gradle event logs to the output directory",
    ).flag()

    private val stacktrace: Boolean by option(
        "--stacktrace",
        help = "Print a stack trace on error"
    ).flag()

    private val gradleArgs: List<String> by argument(
        name = "ARGS",
        help = "Extra arguments to pass to Gradle"
    ).multiple()

    init {
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun run() {
        val logger = Logger(logLevel = logLevel, stacktrace = stacktrace)

        val appHome = System.getProperty("org.nixos.gradle2nix.share")?.let(::File)
            ?: error("could not locate the /share directory in the gradle2nix installation: ${System.getenv("APP_HOME")}")
        val gradleHome =
            System.getenv("GRADLE_USER_HOME")?.let(::File) ?: File("${System.getProperty("user.home")}/.gradle")
        val config = Config(
            appHome,
            gradleHome,
            gradleVersion,
            gradleJdk,
            gradleArgs,
            outDir ?: projectDir,
            projectDir,
            tasks,
            logger,
            dumpEvents
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
        } else {
            metadata.deleteOnExit()
        }

        val buildSrcs = connect(config).use { connection ->
            val root = runBlocking { connection.buildModel() }
            val builds: List<GradleBuild> = buildList {
                add(root)
                addAll(root.editableBuilds)
            }
            builds.mapNotNull { build ->
                build.rootProject.projectDirectory.resolve("buildSrc").takeIf { it.exists() }
            }
        }

        val dependencySets = mutableListOf<DependencySet>()

        connect(config).use { connection ->
            dependencySets.add(runBlocking { connection.build(config) })
        }

        for (buildSrc in buildSrcs) {
            connect(config, buildSrc).use { connection ->
                dependencySets.add(runBlocking { connection.build(config) })
            }
        }

        val env = try {
            processDependencies(config, dependencySets)
        } catch (e: Throwable) {
            logger.error("dependency parsing failed", e)
        }

        config.outDir.mkdirs()

        val outLockFile = config.outDir.resolve(lockFile)
        logger.info("Writing lock file to $outLockFile")
        outLockFile.outputStream().buffered().use { output ->
            JsonFormat.encodeToStream(env, output)
        }

        val inNixFile = config.appHome.resolve("gradle.nix").takeIf { it.exists() }
            ?: error("Couldn't locate gradle.nix in the the gradle2nix installation: ${config.appHome}")
        val outNixFile = config.outDir.resolve(nixFile)
        logger.info("Writing Nix builder to $outNixFile")
        inNixFile.copyTo(outNixFile, overwrite = true)
    }
}

fun main(args: Array<String>) {
    Gradle2Nix().main(args)
}
