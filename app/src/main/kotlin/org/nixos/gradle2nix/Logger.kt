package org.nixos.gradle2nix

import java.io.PrintStream
import kotlin.system.exitProcess

class Logger(
    val out: PrintStream = System.err,
    val logLevel: LogLevel = LogLevel.warn,
    val stacktrace: Boolean = false,
) {

    fun debug(message: String, error: Throwable? = null) {
        if (logLevel <= LogLevel.debug) {
            out.println("[DEBUG] $message")
            printError(error)
        }
    }

    fun info(message: String, error: Throwable? = null) {
        if (logLevel <= LogLevel.info) {
            out.println("[INFO] $message")
            printError(error)
        }
    }

    fun warn(message: String, error: Throwable? = null) {
        if (logLevel <= LogLevel.warn) {
            out.println("[WARN] $message")
            printError(error)
        }
    }

    fun error(message: String, error: Throwable? = null): Nothing {
        out.println("[ERROR] $message")
        printError(error)
        exitProcess(1)
    }

    private fun printError(error: Throwable?) {
        if (error == null) return
        error.message?.let { println("  Cause: $it") }
        if (stacktrace) error.printStackTrace(out)
    }

    operator fun component1() = ::info
    operator fun component2() = ::warn
    operator fun component3() = ::error
}
