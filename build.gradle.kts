plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.pluginPublish) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.stutter) apply false
}

group = "org.nixos.gradle2nix"
version = property("VERSION") ?: "unspecified"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks {
    wrapper {
        gradleVersion = "8.3"
        distributionType = Wrapper.DistributionType.ALL
    }
}
