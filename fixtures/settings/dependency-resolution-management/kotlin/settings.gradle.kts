dependencyResolutionManagement {
    repositories {
        maven { url = uri(System.getProperty("org.nixos.gradle2nix.m2")) }
    }
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
}
