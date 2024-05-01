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
      inherit (nixpkgs.lib)
        concatMapAttrs
        genAttrs
        isFunction;

      eachSystem = f: genAttrs
        [
          "aarch64-darwin"
          "aarch64-linux"
          "x86_64-darwin"
          "x86_64-linux"
        ]
        (system: f
          (import nixpkgs
            {
              inherit system;
              overlays = [
                inputs.devshell.overlays.default
                inputs.nix-fetcher-data.overlays.default
                self.overlays.default
              ];
            }));
    in
    {
      packages = eachSystem (pkgs:
        {
          inherit (pkgs) clj-builder deps-lock mk-deps-cache
            fake-git
            mkCljBin mkCljLib mkGraalBin customJdk
            cljHooks
            mkBabashka bbTasksFromFile;

          babashka = pkgs.mkBabashka { };
          babashka-unwrapped = pkgs.mkBabashka { wrap = false; };

          docs = pkgs.callPackage ./extra-pkgs/docs { inherit pkgs; };
        });

      devShells = eachSystem (pkgs: {
        default =
          pkgs.devshell.mkShell {
            packages = [
              pkgs.jq
              pkgs.clojure
              pkgs.graalvm-ce
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
