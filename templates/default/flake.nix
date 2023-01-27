{
  description = "A clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };
  outputs = { self, nixpkgs, flake-utils, clj-nix }:

    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        cljpkgs = clj-nix.packages."${system}";
      in

      {
        packages = {

          hello-clj = cljpkgs.mkCljBin {
            projectSrc = ./.;
            name = "me.lafuente/cljdemo";
            main-ns = "hello.core";
            jdkRunner = pkgs.jdk17_headless;
          };

          hello-jdk = cljpkgs.customJdk {
            cljDrv = self.packages."${system}".hello-clj;
            locales = "en,es";
          };

          hello-graal = cljpkgs.mkGraalBin {
            cljDrv = self.packages."${system}".hello-clj;
          };

        };
      });

}
