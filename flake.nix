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

            clj-builder = pkgs.callPackage utils.clj-builder { };

            deps-lock = pkgs.callPackage utils.deps-lock
              {
                clj-builder = self.packages."${system}".clj-builder;
              };

            mkCljBin = attrs: pkgs.callPackage ./pkgs/mkCljBin.nix
              ({ clj-builder = self.packages."${system}".clj-builder; }
              // attrs);

            mkGraalBin = attrs: pkgs.callPackage ./pkgs/mkGraalBin.nix attrs;

            customJdk = pkgs.callPackage ./pkgs/customJdk.nix;

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

    };

}
