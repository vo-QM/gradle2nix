package org.nixos.gradle2nix

import java.io.PrintStream
import kotlin.system.exitProcess

class Logger(
    val out: PrintStream = System.err,
    val verbose: Boolean,
    val stacktrace: Boolean = false
) {

    fun log(message: String, error: Throwable? = null) {
        if (!verbose) return
        out.println(message)
        if (error == null) return
        error.message?.let { println("  Cause: $it") }
        if (stacktrace) error.printStackTrace(out)
    }

    fun warn(message: String, error: Throwable? = null) {
        out.println("Warning: $message")
        if (error == null) return
        error.message?.let { println("  Cause: $it") }
        if (stacktrace) error.printStackTrace(out)
    }

    fun error(message: String, error: Throwable? = null): Nothing {
        out.println("Error: $message")
        if (error != null) {
            error.message?.let { println("  Cause: $it") }
            if (stacktrace) error.printStackTrace(out)
        }
        exitProcess(1)
    }

    operator fun component1() = ::log
    operator fun component2() = ::warn
    operator fun component3() = ::error
}
