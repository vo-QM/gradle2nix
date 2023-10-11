plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

configurations {
    register("share")
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(project(":model"))
    implementation(libs.clikt)
    implementation(libs.gradle.toolingApi)
    implementation(libs.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    implementation(libs.okio)
    implementation(libs.xmlutil)

    "share"(project(":plugin", configuration = "shadow"))

    testRuntimeOnly(kotlin("reflect"))
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner)
}

application {
    mainClass.set("org.nixos.gradle2nix.MainKt")
    applicationName = "gradle2nix"
    applicationDefaultJvmArgs += "-Dorg.nixos.gradle2nix.share=@APP_HOME@/share"
    applicationDistribution
        .from(configurations.named("share"))
        .into("share")
        .rename("plugin.*\\.jar", "plugin.jar")
}

kotlin {
    jvmToolchain(11)
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
        dependsOn(installDist)
        doFirst {
            systemProperties("org.nixos.gradle2nix.share" to installDist.get().destinationDir.resolve("share"))
        }
    }

    startScripts {
        doLast {
            unixScript.writeText(
                unixScript.readText()
                    .replace("@APP_HOME@", "\\\"\$APP_HOME\\\"")
                    .replace(Regex("DEFAULT_JVM_OPTS=\'(.*)\'")) { match ->
                        "DEFAULT_JVM_OPTS=${match.groupValues[1]}"
                    }
            )
            windowsScript.writeText(windowsScript.readText().replace("@APP_HOME@", "%APP_HOME%"))
        }
    }

    withType<Test> {
        notCompatibleWithConfigurationCache("huh?")
        dependsOn(installDist)
        doFirst {
            if (updateGolden.isPresent) {
                systemProperty("org.nixos.gradle2nix.update-golden", "")
            }
            systemProperties(
                "org.nixos.gradle2nix.share" to installDist.get().destinationDir.resolve("share"),
                "org.nixos.gradle2nix.m2" to rootDir.resolve("fixtures/repositories/m2").toURI().toString()
            )
        }
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
