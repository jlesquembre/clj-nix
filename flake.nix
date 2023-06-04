{
  description = "clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    devshell = {
      url = "github:numtide/devshell";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    nix-fetcher-data = {
      url = "github:jlesquembre/nix-fetcher-data";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, ... }@inputs:

    flake-utils.lib.eachDefaultSystem
      (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [
              inputs.devshell.overlays.default
              inputs.nix-fetcher-data.overlays.default
              self.overlays.default
            ];
          };
        in
        {
          packages = {

            inherit (pkgs) clj-builder deps-lock mk-deps-cache
              mkCljBin mkCljLib mkGraalBin customJdk
              mkBabashka bbTasksFromFile;

            babashka = pkgs.mkBabashka { };
            babashka-unwrapped = pkgs.mkBabashka { wrap = false; };
          };

          devShells.default =
            pkgs.devshell.mkShell {
              packages = [
                # pkgs.nix-prefetch-git
                pkgs.jq
                pkgs.clojure
                pkgs.graalvmCEPackages.graalvm19-ce
                pkgs.bats
                pkgs.envsubst
                pkgs.mustache-go
                pkgs.diffutils
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
                {
                  name = "tests";
                  help = "Run tests with bats";
                  command =
                    ''
                      bats --timing test
                    '';
                }
                {
                  name = "dummy-project";
                  help = "Creates a dummy clj-nix project";
                  command =
                    ''
                      project_dir="$(mktemp -d clj-nix.XXXXX --tmpdir)/clj-nix_project"
                      mkdir -p "$project_dir"
                      nix flake new --template ${self} "$project_dir"
                      echo 'cljnixUrl: ${self}' | mustache "${self}/test/integration/flake.template" > "$project_dir/flake.nix"
                      echo "New dummy project: $project_dir"
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
