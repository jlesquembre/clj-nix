# Nix build with babashka

## Quickstart

```nix
mkBabashkaDerivation {
  build = ./my/build.clj;
  deps-build = [ pkgs.gcc ];
}
```

```clojure
(require '[babashka.process :refer [shell]])

(defn build
  [{:keys [out src]}]
  (shell {:dir src} (format "./configure --prefix=%s" out))
  (shell {:dir src} "make install"))

(def version  "2.12.1");

(def pkg
  {:name "hello"
   :version version
   :src {:fetcher :fetchurl
         :url (format "mirror://gnu/hello/hello-%s.tar.gz" version)
         :hash "sha256-jZkUKv2SV28wsM18tCqNxoCZmLxdYH2Idh9RLibH2yA="}
   :build build})
```

## mkBabashkaDerivation

Nix function to generate a derivation with babashka.

- **build**: Path to the babashka build script.
- **deps**: List of runtime dependencies. (Default: `[ ]`)
- **deps-build**: List of build dependencies. (Default: `[ ]`)
- **outputs**: List of Nix derivations outputs. (Default: `[ "out" ]`)
- **babashka**: Babashka version to use. (Default: `[ pkgs.babashka ]`)

## Build script

The build script must define a var called `pkg`. e.g.:

```clojure
(def pkg
  {:name "hello"
   :version "1.0"
   :src {:fetcher :fetchurl
         :url "mirror://gnu/hello/hello-1.0.tar.gz"
         :hash "sha256-jZkUKv2SV28wsM18tCqNxoCZmLxdYH2Idh9RLibH2yA="}
   :build build-fn
```

Those 4 keys are mandatory.

- **:src**: Maps to a nix fetcher, see `./lib/fetchSrc.nix` for a list of
  possible fetchers
- **:build**: Takes 1 hash map as argument, with the following keys:
  - **env**: The enviroment variables, as provided by the nix builder.
  - **src**: Path to the source code in the nix sandbox
  - **outputs**: List of outputs, as defined in the `mkBabashkaDerivation` nix
    function
  - **out**: Path to the `out` output path (e.g.: `/nix/store/xxx-name`). It's
    convenient since in most cases `out` is the only output in `outputs`.

## Demo

In this project, you can run

```bash
nix build -L .#bb-drv-demo
```
