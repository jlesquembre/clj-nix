{
  mkBabashkaDerivation =
    babashka: # nixpkgs.babashka
    sys: # nixpkgs.system

    { name                            # The name of the derivation
    , src ? null                      # The derivation's sources
    , packages ? [ ]                  # Packages provided to the realisation process
    , system ? sys                    # The build system
    , build ? ""                      # The build script itself
    , debug ? true                    # Run in debug mode
    , outputs ? [ "out" ]             # Outputs to provide
      # , envFile ? ../nuenv/user-env.nu  # Nushell environment passed to build phases
    , ...                             # Catch user-supplied env vars
    }@attrs:

    let
      # Gather arbitrary user-supplied environment variables
      reservedAttrs = [
        "build"
        "debug"
        "envFile"
        "name"
        "outputs"
        "packages"
        "src"
        "system"
        "__bb_builder"
        "__bb_debug"
        "__bb_env"
        "__bb_extra_attrs"
        "__bb_binary"
      ];

      extraAttrs = removeAttrs attrs reservedAttrs;
    in
    derivation ({
      # Core derivation info
      inherit
        # envFile
        name outputs packages src system;

      # Realisation phases (just one for now)
      inherit build;

      # Build logic
      builder = "${babashka}/bin/bb"; # Use Babashka :-)
      # args = [ ../bbenv/bootstrap.clj ]; # Run a bootstrap script that then runs the builder
      args = [ "--init" ../bootstrap.clj build ]; # Run a bootstrap script that then runs the builder

      # When this is set, Nix writes the environment to a JSON file at
      # $NIX_BUILD_TOP/.attrs.json. This approach is generally cleaner than
      # parsing environment variables as strings.
      __structuredAttrs = true;

      # Attributes passed to the environment (prefaced with __bb_ to avoid naming collisions)
      # __bb_builder = ../nuenv/builder.nu;
      __bb_debug = debug;
      # __bb_env = [ ../nuenv/env.nu ];
      __bb_extra_attrs = extraAttrs;
      __bb_binary = "${babashka}/bin/bb";
    } // extraAttrs);
}
