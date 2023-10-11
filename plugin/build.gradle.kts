import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradle.plugin-publish")
    id("com.github.johnrengelman.shadow")
}

dependencies {
    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly(kotlin("reflect"))
    implementation(project(":model"))
    implementation(libs.serialization.json)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

gradlePlugin {
    plugins {
        register("gradle2nix") {
            id = "org.nixos.gradle2nix"
            displayName = "gradle2nix"
            description = "Expose Gradle tooling model for the gradle2nix tool"
            implementationClass = "org.nixos.gradle2nix.Gradle2NixPlugin"
        }
    }
}

tasks {
    jar {
        manifest {
            attributes["Implementation-Version"] = archiveVersion.get()
            attributes["Implementation-Title"] = "Gradle2Nix Plugin"
            attributes["Implementation-Vendor"] = "Tad Fisher"
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("kotlin", "${project.group}.shadow.kotlin")
        relocate("kotlinx.serialization", "${project.group}.shadow.serialization")
        relocate("net.swiftzer.semver", "${project.group}.shadow.semver")
        relocate("org.intellij", "${project.group}.shadow.intellij")
        relocate("org.jetbrains", "${project.group}.shadow.jetbrains")
    }

    validatePlugins {
        enableStricterValidation.set(true)
    }
}
