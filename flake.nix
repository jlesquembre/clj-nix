{
  description = "clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    devshell = {
      url = "github:numtide/devshell";
      inputs.nixpkgs.follows = "nixpkgs";
    };

  };
  outputs = { self, nixpkgs, flake-utils, ... }@inputs:


    flake-utils.lib.eachDefaultSystem
      (system:
        let
          pkgs = inputs.nixpkgs.legacyPackages.${system};
          utils = import ./pkgs/utils.nix;
        in
        {
          packages = {

            clj-builder = pkgs.callPackage ./pkgs/cljBuilder.nix { };

            deps-lock = pkgs.callPackage ./pkgs/depsLock.nix
              {
                clj-builder = self.packages."${system}".clj-builder;
              };

            mk-deps-cache = pkgs.callPackage ./pkgs/mkDepsCache.nix;

            mkCljBin = pkgs.callPackage ./pkgs/mkCljBin.nix
              {
                clj-builder = self.packages."${system}".clj-builder;
                mk-deps-cache = self.packages."${system}".mk-deps-cache;
              };

            mkCljLib = pkgs.callPackage ./pkgs/mkCljLib.nix
              {
                clj-builder = self.packages."${system}".clj-builder;
                mk-deps-cache = self.packages."${system}".mk-deps-cache;
              };

            mkGraalBin = pkgs.callPackage ./pkgs/mkGraalBin.nix { };

            customJdk = pkgs.callPackage ./pkgs/customJdk.nix { };

            cljTasks = pkgs.callPackage ./pkgs/cljTasks
              {
                mkCljBin = self.packages."${system}".mkCljBin;
                mkGraalBin = self.packages."${system}".mkGraalBin;
              };

          };
          devShell =
            let
              pkgs = import nixpkgs {
                inherit system;
                overlays = [ inputs.devshell.overlay ];
              };
            in
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

        }) //

    {
      lib = import ./helpers.nix;
      defaultTemplate = {
        path = ./templates/default;
        description = "A simple clj-nix project";
      };

    };

}
