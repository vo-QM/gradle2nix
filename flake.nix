{
  description = "Wrap Gradle builds with Nix";

  inputs = {
    flake-utils.url = "github:numtide/flake-utils";
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable-small";
  };

  outputs = { self, flake-utils, nixpkgs, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

      in {
        builders.default = pkgs.callPackage ./gradle.nix {};

        packages.default = pkgs.callPackage ./default.nix {};

        apps.default = {
          type = "app";
          program = "${self.packages.${system}.default}/bin/gradle2nix";
        };
      });
}
