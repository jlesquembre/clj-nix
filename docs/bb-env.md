## Nix Babashka builder

This a very experimental [Nix][] builder that uses [Babashka][] as an
alternative builder for [Nix][] ([Nixpkgs][] is build on top of
`stdenv.mkDerivation`, which is developed with [Bash][])

All the examples in this document can be found here:

https://github.com/jlesquembre/bb-pkgs

### Installation

Add the following to your `flake.nix` inputs:

```
clj-nix.url = "github:jlesquembre/clj-nix";
```

The flake provides a helper function, `babashkaEnv`, which returns a function
that we can use to create derivations. This function is basically a replacement
for `stdenv.mkDerivation`. We recommend naming the function you create with
`babashkaEnv` as `mkBabashkaDerivation`.

For a working example see:

https://github.com/jlesquembre/bb-pkgs/blob/main/flake.nix

### Structure

When creating derivations with `mkBabashkaDerivation`, a specific directory
structure is expected. Each derivation must live in its own directory (which you
usually want to name the same as your package), and the directory must contain
at least one file called `package.clj`. Under the same directory, you can put
any other files required by the derivation.

For example, with two derivations, the file structure would look like this:

```
pkgs
├── caddy
│   └── package.clj
└── nginx
    └── package.clj
```

### Usage

Let's see an example of a `package.clj` file:

```clojure
(ns hello.package

(def version "2.12.1");

(defn version-url
  [v]
  (format "mirror://gnu/hello/hello-%s.tar.gz" v))

(def pkg
  {:name "hello"
   :version version
   :deps []
   :build-deps [:gcc]
   :src {:fetcher :fetchurl
         :url (version-url version)
         :hash "sha256-jZkUKv2SV28wsM18tCqNxoCZmLxdYH2Idh9RLibH2yA="}
   :build (fn [_args] "Build something...")})
```

For a full working example, see:

https://github.com/jlesquembre/bb-pkgs/blob/main/pkgs/hello/package.clj

To create a derivation, the `package.clj` must:

- Define a namespace, e.g.: `(ns foo.package)`
- Create a var called `pkg`. It must be a hash map with, at least, 3 keys,
  `:name`, `:version`, and `:build`. Optionally, you can also define `:deps`,
  `:build-deps` and `:src`.

  - `:name`: The name of the derivation. (It can be any name.)

  - `:version`: The version of the derivation. (It can be any alphanumeric
    string.)

  - `:deps`: The runtime dependencies. (A list of strings or keywords. The name
    should be a valid derivation name on
    [nixpkgs](https://search.nixos.org/packages)

  - `:build-deps`: Similar to `:deps`, but for dependecies only needed at build
    time.

  - `:src`: The source to build the derivation, similar to the src attribute of
    mkDerivation. See the next section for more details.

  - `:build`: A function that builds the derivation. See the details in the next
    section.

#### `:src`

This is similar to the `src` attribute of `mkDerivation`. It must be a hash map
with only one key present: `fetcher`. The `fetcher` key should contain the name
of one of the
[nixpkgs fetchers](https://nixos.org/manual/nixpkgs/stable/#chap-pkgs-fetchers).
All other arguments defined are passed directly to the fetcher.

#### `:build`

The build function takes one argument, a hash map. Most of the values in this
hash map are environment variables set by `stdenv.mkDerivation`. Here are the
most important ones:

- `src`: The path to the temporary build directory where the source has been
  copied and unpacked (if needed).
- `out`: The path to the Nix store where you can copy files. A derivation must,
  at a minimum, create this directory.

#### Dependecies

If you want to add a dependency created with `mkBabashkaDerivation` (instead of
a Nixpkgs dependency), namespace your dependency with `:bb`, e.g.:
`[:bash :bb/my-pkg]`. Here, `bash` is provided by [Nixpkgs][], while `bb/my-pkg`
is a derivation created with `mkBabashkaDerivation`. For that to work, you need
to define the `bb-pkgs` argument to `babashkaEnv`, e.g.:

```nix
outputs = { self, nixpkgs, clj-nix, ... }@inputs:

  let
    supportedSystems = [ "aarch64-darwin" "aarch64-linux" "x86_64-darwin" "x86_64-linux" ];

    eachSystem = f: nixpkgs.lib.genAttrs supportedSystems (system: f {
      pkgs = import nixpkgs { inherit system; };
      inherit system;
    });
  in
  {
    packages = eachSystem ({ pkgs, system }:
      let
        mkBabashkaDerivation = clj-nix.outputs.packages.${system}.babashkaEnv {
          inherit system pkgs;
          bb-pkgs = self.outputs.packages.${system};
        };
      in
      {
        hello = mkBabashkaDerivation { pkg = ./pkgs/hello; };
        simple = mkBabashkaDerivation { pkg = ./pkgs/simple; };
      });
  };
```

#### Overrides

It is also posible to override a derivation. To do that, the
`mkBabashkaDerivation` accepts a second argument, `override`:

```nix
mkBabashkaDerivation {
  pkg = ./pkgs/hello;
  override = ./pkgs/hello_override;
}
```

In the directory of your override, create a file called `override.clj`. In that
file, you must define a function called `override`. It takes only one argument,
the `pkg` defined in the derivation, and it must return a modified version of
it. E.g.:

```clojure
(ns hello-override.override
  (:require [hello.package :as hello]))

(def version "2.10")

(defn override
  [pkg]
  (-> pkg
      (assoc :version version)
      (assoc-in [:src :url] (hello/version-url version))
      (assoc-in [:src :hash] "sha256-MeBmE3qWJnbon2nRtlOC3pWn732RS4y5VvQepy4PUWs=")))
```

### Similar project

A non-exhaustive list of projects providing alternative Nix builders:

- [nuenv: A Nushell environment for Nix](https://github.com/DeterminateSystems/nuenv)
- [nix-reinventing-the-wheel](https://github.com/gytis-ivaskevicius/nix-reinventing-the-wheel)

[Babashka]: https://babashka.org/
[Nix]: https://nixos.org/
[Bash]: https://www.gnu.org/software/bash/
[Nixpkgs]: https://nixos.org/manual/nixpkgs/stable/
