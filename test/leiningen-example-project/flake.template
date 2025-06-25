# vi: ft=nix
# Local Variables:
# mode: nix
# End:
{
  description = "A clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix = {
      url = "{{cljnixUrl}}";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };
  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      clj-nix,
    }:

    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ clj-nix.overlays.default ];
        };
      in

      {
        packages = {
          mkCljBin-test = pkgs.mkCljBin {
            projectSrc = ./.;
            name = "me.lafuente/cljdemo";
            main-ns = "hello.core";
            jdkRunner = pkgs.jdk_headless;
            enableLeiningen = true;
            buildCommand = "lein uberjar";
          };

          mkCljBin-test-with-tests = pkgs.mkCljBin {
            projectSrc = ./.;
            name = "me.lafuente/cljdemo";
            main-ns = "hello.core";
            jdkRunner = pkgs.jdk_headless;
            enableLeiningen = true;
            buildCommand = "lein uberjar";
            doCheck = true;
            checkPhase = "lein test";
          };
        };
      }
    );
}
