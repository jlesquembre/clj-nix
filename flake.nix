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
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ inputs.devshell.overlay self.overlay ];
          };
        in
        {
          packages = {

            inherit (pkgs) clj-builder deps-lock mk-deps-cache
              mkCljBin mkCljLib mkGraalBin customJdk;

          };

          devShell =
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

      overlay = final: prev: {
        clj-builder = final.callPackage ./pkgs/cljBuilder.nix { };
        deps-lock = final.callPackage ./pkgs/depsLock.nix { };
        mk-deps-cache = final.callPackage ./pkgs/mkDepsCache.nix;
        mkCljBin = final.callPackage ./pkgs/mkCljBin.nix { };
        mkCljLib = final.callPackage ./pkgs/mkCljLib.nix { };
        mkGraalBin = final.callPackage ./pkgs/mkGraalBin.nix { };
        customJdk = final.callPackage ./pkgs/customJdk.nix { };
      };
    };

}
