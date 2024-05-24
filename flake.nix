{
  description = "Wrap Gradle builds with Nix";

  inputs = {
    flake-compat.url = "https://flakehub.com/f/edolstra/flake-compat/1.tar.gz";
    flake-utils.url = "github:numtide/flake-utils";
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable-small";
  };

  outputs = { self, flake-utils, nixpkgs, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

      in {
        packages.default = pkgs.callPackage ./gradle2nix.nix {};

        apps.default = {
          type = "app";
          program = "${self.packages.${system}.default}/bin/gradle2nix";
        };
      });
}
