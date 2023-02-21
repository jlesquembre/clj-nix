{
  description = "clj-nix flake";

  nixConfig = {
    extra-substituters = [ "https://clj-nix.cachix.org" ];
    extra-trusted-public-keys = [ "clj-nix.cachix.org-1:ftfCDUxeGYYoD9MSQARsBj5WyViAh2K/5Dq8nIDH150=" ];
  };

  inputs = {
    # nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    nixpkgs.url = "github:jlesquembre/nixpkgs/graalvm19-ce";
    flake-utils.url = "github:numtide/flake-utils";
    devshell = {
      url = "github:numtide/devshell";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, ... }@inputs:

    flake-utils.lib.eachDefaultSystem
      (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ inputs.devshell.overlay self.overlays.default ];
          };
        in
        {
          packages = {

            bb-loom = pkgs.mkBabashka { graalvm = pkgs.graalvm19-ce; };

            inherit (pkgs) clj-builder deps-lock mk-deps-cache
              mkCljBin mkCljLib mkGraalBin customJdk
              mkBabashka bbTasksFromFile;
          };

          devShells.default =
            pkgs.devshell.mkShell {
              packages = [
                pkgs.nix-prefetch-git
                pkgs.jq
                pkgs.clojure
                pkgs.graalvmCEPackages.graalvm17-ce
              ];
              commands = [
                {
                  name = "update-deps";
                  help = "Update builder-lock.json and clojure-deps.edn";
                  command =
                    ''
                      clj -X cljnix.bootstrap/as-json :deps-path '"deps.edn"' | jq . > pkgs/builder-lock.json
                      clj -X cljnix.core/clojure-deps-str > src/clojure-deps.edn
                    '';
                }
                {
                  name = "kaocha";
                  help = "Run tests with kaocha";
                  command =
                    ''
                      clojure -M:test -m kaocha.runner "$@"
                    '';
                }
              ];
            };

          # Deprecated
          devShell = self.devShells.${system}.default;

        }) //

    {
      lib = import ./helpers.nix;

      templates.default = {
        path = ./templates/default;
        description = "A simple clj-nix project";
      };

      # Deprecated
      defaultTemplate = self.templates.default;

      overlays.default = final: prev: {
        clj-builder = final.callPackage ./pkgs/cljBuilder.nix { };
        deps-lock = final.callPackage ./pkgs/depsLock.nix { };
        mk-deps-cache = final.callPackage ./pkgs/mkDepsCache.nix;
        mkCljBin = final.callPackage ./pkgs/mkCljBin.nix { };
        mkCljLib = final.callPackage ./pkgs/mkCljLib.nix { };
        mkGraalBin = final.callPackage ./pkgs/mkGraalBin.nix { };
        customJdk = final.callPackage ./pkgs/customJdk.nix { };

        mkBabashka = final.callPackage ./extra-pkgs/babashka { };
        bbTasksFromFile = final.callPackage ./extra-pkgs/bbTasks { };
      };

      # Deprecated
      overlay = self.overlays.default;
    };
}
