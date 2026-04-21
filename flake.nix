{
  description = "clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    devshell = {
      url = "github:numtide/devshell";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    nix-fetcher-data = {
      url = "github:jlesquembre/nix-fetcher-data";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, ... }@inputs:

    let
      supportedSystems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-darwin"
        "x86_64-linux"
      ];

      eachSystem = f: nixpkgs.lib.genAttrs supportedSystems (system: f {
        pkgs = import nixpkgs
          {
            inherit system;
            overlays = [
              inputs.devshell.overlays.default
              inputs.nix-fetcher-data.overlays.default
              self.overlays.default
            ];
          };
        inherit system;
      });
    in
    {
      packages = eachSystem ({ pkgs, system }:
        {
          inherit (pkgs) clj-builder deps-lock mk-deps-cache
            fake-git
            mkCljBin mkCljLib mkGraalBin customJdk
            cljHooks
            mkBabashka bbTasksFromFile;

          babashka = pkgs.mkBabashka { };
          babashka-unwrapped = pkgs.mkBabashka { wrap = false; };

          docs = pkgs.callPackage ./extra-pkgs/docs { inherit pkgs; };

          babashkaEnv = import ./extra-pkgs/bbenv/lib/bbenv.nix;

        });

      devShells = eachSystem ({ pkgs, ... }: {
        default =
          pkgs.devshell.mkShell {
            packages = [
              pkgs.jq
              pkgs.clojure
              pkgs.babashka
              pkgs.graalvmPackages.graalvm-ce
              pkgs.bats
              pkgs.envsubst
              pkgs.mustache-go
              pkgs.diffutils
            ];
            commands = [
              {
                name = "update-clojure-deps";
                category = "dependencies";
                help = "Update Clojure dependency versions in deps.edn";
                command = ''
                  clojure -Sdeps '{:deps {com.github.liquidz/antq {:mvn/version "RELEASE"}}}' -M \
                    -m antq.core \
                    -d . \
                    --upgrade \
                    --force \
                    --skip=github-action
                '';
              }
              {
                name = "update-lock-files";
                category = "dependencies";
                help = "Regenerate all builder-lock.json and deps-lock.json files";
                command = ''
                  bb ./scripts/newer_clojure_versions.bb
                  clojure -Sdeps '{:deps {com.github.liquidz/antq {:mvn/version "RELEASE"}}}' \
                    -M \
                    -m antq.core \
                    --upgrade \
                    --force
                  clj -X cljnix.bootstrap/as-json :deps-path '"deps.edn"' | jq . > pkgs/builder-lock.json
                  clj -X cljnix.core/clojure-deps-str > src/clojure-deps.edn
                  (cd ./templates/default && nix run ../..#deps-lock)
                  (cd ./test/leiningen-example-project && \
                    nix run ../..#deps-lock -- --lein --lein-profiles foobar && \
                    mv deps-lock.json deps-lock-foobar-profile.json)
                  (cd ./test/leiningen-example-project && \
                    nix run ../..#deps-lock -- --lein)
                '';
              }
              {
                name = "dummy-project";
                category = "scaffolding";
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
              {
                name = "tests-unit";
                category = "test categories";
                help = "Run Clojure unit tests";
                command = ''
                  clojure -M:test -m kaocha.runner :unit "$@"
                '';
              }
              {
                name = "tests-integration";
                category = "test categories";
                help = "Run Clojure integration tests";
                command = ''
                  clojure -M:test -m kaocha.runner :integration "$@"
                '';
              }
              {
                name = "tests-e2e";
                category = "test categories";
                help = "Run end-to-end tests with bats";
                command = ''
                  bats --timing test
                '';
              }
              {
                name = "tests-all";
                category = "test categories";
                help = "Run all tests (Clojure and bats)";
                command = ''
                  echo "Running Clojure unit tests..."
                  clojure -M:test -m kaocha.runner :unit
                  echo "Running Clojure integration tests..."
                  clojure -M:test -m kaocha.runner :integration
                  echo "Running bats end-to-end tests..."
                  bats --timing test
                '';
              }
              {
                name = "tests-bats";
                category = "test runners";
                help = "Run bats test runner (defaults to test directory if no args provided)";
                command = ''
                  if [ $# -eq 0 ]; then
                    bats --timing test
                  else
                    bats "$@"
                  fi
                '';
              }
              {
                name = "tests-kaocha";
                category = "test runners";
                help = "Run kaocha test runner with optional parameters";
                command = ''
                  if [ $# -eq 0 ]; then
                    clojure -M:test -m kaocha.runner
                  else
                    clojure -M:test -m kaocha.runner "$@"
                  fi
                '';
              }
            ];
          };
      });

      lib = import ./helpers.nix { clj-nix_overlay = self.overlays.default; };

      templates.default = {
        path = ./templates/default;
        description = "A simple clj-nix project";
      };

      overlays.default = final: prev:
        let common = final.callPackage ./pkgs/common.nix { }; in
        {
          fake-git = final.callPackage ./pkgs/fakeGit.nix { };
          deps-lock = final.callPackage ./pkgs/depsLock.nix { inherit common; };
          clj-builder = final.callPackage ./pkgs/cljBuilder.nix { inherit common; };
          mk-deps-cache = final.callPackage ./pkgs/mkDepsCache.nix;
          mkCljBin = final.callPackage ./pkgs/mkCljBin.nix { inherit common; };
          mkCljLib = final.callPackage ./pkgs/mkCljLib.nix { };
          mkGraalBin = final.callPackage ./pkgs/mkGraalBin.nix { };
          customJdk = final.callPackage ./pkgs/customJdk.nix { };

          cljHooks = final.callPackage ./pkgs/cljHooks.nix { inherit common; };

          mkBabashka = final.callPackage ./extra-pkgs/babashka { };
          bbTasksFromFile = final.callPackage ./extra-pkgs/bbTasks { };
        }
        // inputs.nix-fetcher-data.overlays.default final prev;

    };
}
