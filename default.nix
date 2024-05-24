{ pkgs ? import <nixpkgs> {} }:

with pkgs;

let
  buildGradle = callPackage ./gradle.nix {};

  gradle2nix = buildGradle {
    pname = "gradle2nix";
    version = "2.0.0";
    lockFile = ./gradle.lock;

    src = lib.cleanSourceWith {
      filter = lib.cleanSourceFilter;
      src = lib.cleanSourceWith {
        filter = path: type: let baseName = baseNameOf path; in !(
          (type == "directory" && (
            baseName == "build" ||
            baseName == ".idea" ||
            baseName == ".gradle"
          )) ||
          (lib.hasSuffix ".iml" baseName)
        );
        src = ./.;
      };
    };

    gradleFlags = [ "installDist" ];

    installPhase = ''
      mkdir -p $out
      cp -r app/build/install/gradle2nix/* $out/
    '';

    passthru = {
      build = buildGradle;
      plugin = "${gradle2nix}/share/plugin.jar";
    };

    meta = with lib; {
      inherit (gradle.meta) platforms;
      description = "Wrap Gradle builds with Nix";
      homepage = "https://github.com/tadfisher/gradle2nix";
      license = licenses.asl20;
      maintainers = with maintainers; [ tadfisher ];
      mainProgram = "gradle2nix";
    };
  };

in gradle2nix
