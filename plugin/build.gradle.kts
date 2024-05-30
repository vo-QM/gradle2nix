import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradle.plugin-publish")
    id("com.github.johnrengelman.shadow")
}

dependencies {
    shadow(kotlin("stdlib-jdk8"))
    shadow(kotlin("reflect"))
    implementation(project(":model"))
    implementation(libs.serialization.json)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_1_6)
        languageVersion.set(KotlinVersion.KOTLIN_1_6)
        jvmTarget.set(JvmTarget.JVM_1_8)
        optIn.add("kotlin.RequiresOptIn")
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
        relocate("org.intellij", "${project.group}.shadow.intellij")
        relocate("org.jetbrains", "${project.group}.shadow.jetbrains")
    }

    validatePlugins {
        enableStricterValidation.set(true)
    }
}
