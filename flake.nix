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
        {
          packages = let pkgs = import inputs.nixpkgs {
            inherit system;
            overlays = [ self.overlay ];
          }; in
            {
              inherit (pkgs) clj-builder deps-lock mkCljBin mkGraalBin customJdk;
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
                # pkgs.jdk
                pkgs.graalvmCEPackages.graalvm17-ce
              ];
              commands = [
                {
                  name = "update-deps";
                  help = "Update deps-lock.json";
                  command =
                    ''
                      clj -X core/deps-lock :in '"deps.edn"' | jq . > deps-lock.json
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
      overlay = final: prev:
        let
          utils = import ./pkgs/utils.nix;
        in
        {
          clj-builder = prev.callPackage utils.clj-builder { };

          deps-lock = prev.callPackage utils.deps-lock {
            inherit (final) clj-builder;
          };

          mkCljBin = prev.callPackage ./pkgs/mkCljBin.nix {
            inherit (final) clj-builder;
          };

          mkGraalBin = prev.callPackage ./pkgs/mkGraalBin.nix { };

          customJdk = prev.callPackage ./pkgs/customJdk.nix;
        };
    };
}
