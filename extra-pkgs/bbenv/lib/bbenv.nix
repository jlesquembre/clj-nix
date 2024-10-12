{ sys
, pkgs
}:

let
  inherit (pkgs) lib;
  fetchDrvInfo = pkgs.callPackage ./fetchSrc.nix { };
in

{
  mkBabashkaDerivation =

    { babashka ? pkgs.babashka        # Babashka version to use
    , deps ? [ ]
    , deps-build ? [ ]
    , system ? sys                    # The build system
    , build                           # The build script itself
    , debug ? true                    # Run in debug mode
    , outputs ? [ "out" ]             # Outputs to provide
    , ...                             # Catch user-supplied env vars
    }@attrs:

    let
      # Gather arbitrary user-supplied environment variables
      reservedAttrs = [
        "deps"
        "deps-build"
        "build"
        "debug"
        "outputs"
        "system"

        # In the build.clj script
        "name"
        "version"
        "src"

        # "__bb_builder"
        # "__bb_debug"
        # "__bb_env"
        # "__bb_extra_attrs"
        # "__bb_binary"
      ];

      extraAttrs = removeAttrs attrs reservedAttrs;

      # Extract data from the clj build file and save it to the nix store
      bbenv-utils = pkgs.runCommand "bbenv-utils"
        {
          nativeBuildInputs = [ babashka ];
        }
        ''
          mkdir -p $out/src
          cp "${../bbenv_utils.clj}" $out/src/bbenv_utils.clj
          bb --init ${build} -cp $out/src -x 'bbenv-utils/info->json' --out "$out/info.json"
        '';


      drvInfo = fetchDrvInfo bbenv-utils;

      defaultBuildDeps = [
        babashka
        pkgs.gnutar
        pkgs.coreutils
        pkgs.findutils
        pkgs.diffutils
        pkgs.xz.bin
        pkgs.gzip
        pkgs.bzip2.bin
        pkgs.gnused
        pkgs.gnugrep
        pkgs.gnumake
        pkgs.gawk
        pkgs.bash
        pkgs.patch
      ];


      # JDK / Native image don't allow to modify environment variables, but we want to set the PATH
      builder-wrapper =
        let
          path = lib.makeBinPath (defaultBuildDeps ++ deps ++ deps-build);
        in
        pkgs.writers.writeBashBin "bbenv-build-wrapper"
          { }
          ''
            set -o errexit
            set -o nounset
            set -o pipefail
            export PATH="${path}"
            bb --init ${build} -cp "${bbenv-utils}/src" -x "bbenv-utils/mk-derivation" --src "${drvInfo.src}"
          '';

    in

    derivation ({
      inherit (drvInfo) name version;
      inherit outputs deps deps-build system build;

      builder = "${builder-wrapper}/bin/bbenv-build-wrapper";
      # args = [ ];

      # When this is set, Nix writes the environment to a JSON file at
      # $NIX_BUILD_TOP/.attrs.json. This approach is generally cleaner than
      # parsing environment variables as strings.
      __structuredAttrs = true;

    } // extraAttrs);
}
