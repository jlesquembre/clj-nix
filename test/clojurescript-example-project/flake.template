{
  description = "ClojureScript example project for testing";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    cljnix = {
      url = "{{cljnixUrl}}";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, cljnix }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ cljnix.overlays.default ];
        };
      in
      {
        packages = {
          default = pkgs.mkCljsApp {
            projectSrc = ./.;
            name = "cljs-example/app";
            version = "0.1.0";
            buildTarget = "browser";
            buildId = "app";
          };
        };
      }
    );
}
