package org.nixos.gradle2nix

import java.io.File

fun File.isProjectRoot(): Boolean =
    isDirectory && (resolve("settings.gradle").isFile || resolve("settings.gradle.kts").isFile)
