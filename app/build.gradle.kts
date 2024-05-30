plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

configurations.register("share")

dependencies {
    implementation(project(":model"))
    implementation(libs.clikt)
    implementation(libs.gradle.toolingApi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.serialization.json)
    runtimeOnly(libs.slf4j.simple)

    "share"(project(":plugin", configuration = "shadow")) {
        isTransitive = false
    }

    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
}

application {
    mainClass.set("org.nixos.gradle2nix.MainKt")
    applicationName = "gradle2nix"
    applicationDefaultJvmArgs =
        listOf(
            "-Dorg.nixos.gradle2nix.share=@APP_HOME@/share",
            "-Dslf4j.internal.verbosity=ERROR",
        )
    applicationDistribution
        .from(configurations.named("share"))
        .into("share")
        .rename("plugin.*\\.jar", "plugin.jar")
}

sourceSets {
    test {
        resources {
            srcDir("$rootDir/fixtures")
        }
    }
}

val updateGolden = providers.gradleProperty("update-golden")

tasks {
    (run) {
        enabled = false
    }

    startScripts {
        doLast {
            unixScript.writeText(
                unixScript.readText().replace("@APP_HOME@", "'\$APP_HOME'"),
            )
            windowsScript.writeText(
                windowsScript.readText().replace("@APP_HOME@", "%APP_HOME%"),
            )
        }
    }

    // TODO Find out why this fails the configuration cache
    test {
        notCompatibleWithConfigurationCache("contains a Task reference")
        val shareDir = layout.dir(installDist.map { it.destinationDir.resolve("share") })
        doFirst {
            if (updateGolden.isPresent) {
                systemProperty("org.nixos.gradle2nix.update-golden", "")
            }
            systemProperties(
                "org.nixos.gradle2nix.share" to shareDir.get().asFile,
                "org.nixos.gradle2nix.m2" to "http://0.0.0.0:8989/m2",
            )
        }
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
