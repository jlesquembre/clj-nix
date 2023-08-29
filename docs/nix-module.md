# Nix Module

## Example

Minimal example, add a `flake.nix` with something like this:

```nix title="flake.nix"
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix.url = "github:jlesquembre/clj-nix";
  };
  outputs = { self, nixpkgs, flake-utils, clj-nix }:

    flake-utils.lib.eachDefaultSystem (system: {
      packages = {

        default = clj-nix.lib.mkCljApp {
          pkgs = nixpkgs.legacyPackages.${system};
          modules = [
            {
              projectSrc = ./.;
              name = "my.org/clj-project";
              version = "1.0";
              main-ns = "demo.core";

              nativeImage.enable = true;
              # customJdk.enable = true;
            }
          ];
        };

      };
    });
}
```

If you want to build an optimized JDK, instead of a native image, just turn on a
flag :-)

## Options

There are many more options: [Full list of options](./options.md)

!!! NOTE

    If you want to build a binary from you clojure code, I recommended to use the
    Nix module. For more complex cases, you can use the functions `mkCljBin`,
    `customJdk` and `mkGraalBin` directly, but consider those a lower level API.

!!! warning

    The clj-nix Nix module is still experimental and may still undergo breaking changes.

More documentation about modules:
[NixOS modules](https://nixos.org/manual/nixos/stable/index.html#sec-writing-modules)
