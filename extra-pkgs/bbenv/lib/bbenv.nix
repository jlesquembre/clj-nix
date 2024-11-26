{ sys
, pkgs
}:

let
  inherit (pkgs) lib;
  fetchDrvInfo = pkgs.callPackage ./fetchSrc.nix { };
in

{
  mkBabashkaDerivation =

    { babashka ? pkgs.babashka-unwrapped    # Babashka version to use
    , system ? sys                          # The build system
    , pkg                                   # Path to the directory with the package
    , debug ? true                          # Run in debug mode
    , outputs ? [ "out" ]                   # Outputs to provide
    , override ? null                       # Optional override
    , ...                                   # Catch user-supplied env vars
    }@attrs:

    let
      # Gather arbitrary user-supplied environment variables
      reservedAttrs = [
        "deps"
        "deps-build"
        "pkg"
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

      ifOverride = s: (lib.optionalString (! isNull override) s);
      ifElseOverride = s1: s2: (if (isNull override) then s2 else s1);

      # Extract data from the clj build file and save it to the nix store
      bbenv-utils = pkgs.runCommand "bbenv-utils"
        {
          nativeBuildInputs = [ babashka ];
        }
        ''
          mkdir -p $out/src
          cp "${../bbenv_utils.clj}" $out/src/bbenv_utils.clj
          cp "${../helpers.clj}" $out/src/helpers.clj

          pkg=$(stripHash ${pkg})
          cp -r "${pkg}" $out/src/$pkg
          ${ifElseOverride
            ''
              override=$(stripHash ${override})
              cp -r ${override} "$out/src/$override"
            ''
            ''
              mkdir -p $out/src/override
              echo '(ns override.dummy) (def override identity)' > $out/src/override/dummy.clj
            ''
          }

          bb -cp $out/src -x 'bbenv-utils/write-ns-info' \
             --out "$out" \
             --pkg-path "${pkg}/package.clj" \
             --override-path ${ifElseOverride "${override}/override.clj" "$out/src/override/dummy.clj"}

          substituteInPlace "$out/src/bbenv_utils.clj" \
            --replace-fail ',{},' 'package/pkg' \
            --replace-fail ',:override-package,' 'override-package/override' \
            --replace-fail ';[,pkg,' "[$(head -1 $out/ns.txt)" \
            --replace-fail ';[,override,' "[$(head -1 $out/override-ns.txt)"


          bb -cp $out/src -x 'bbenv-utils/write-src-info' \
             --out "$out/src.json" \
             --override ${ifElseOverride "true" "false"}
        '';


      drvInfo = fetchDrvInfo (bbenv-utils + /src.json);

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


      deps-as-json = pkgs.runCommand "extract-deps"
        {
          __structuredAttrs = true;
        }
        ''
          export PATH="${babashka}/bin"
          bb -cp "${bbenv-utils}/src" -x "bbenv-utils/extract-deps"
        '';

      dependencies = lib.importJSON deps-as-json;
      deps = dependencies.deps or [ ];
      build-deps = dependencies.build-deps or [ ];

      drvDeps = builtins.map (dep: builtins.getAttr dep pkgs)
        (deps ++ build-deps);


      # JDK / Native image don't allow to modify environment variables, but we want to set the PATH
      path = lib.makeBinPath (drvDeps ++ defaultBuildDeps);

      builder-wrapper =
        pkgs.writers.writeBashBin "bbenv-build-wrapper"
          { }
          ''
            set -o errexit
            set -o nounset
            set -o pipefail
            export PATH="${path}"
            bb -cp "${bbenv-utils}/src" \
              -x "bbenv-utils/mk-derivation" \
              --src "${drvInfo.src or "nil"}"
          '';

      mapDeps = ds: lib.genAttrs ds (name: (builtins.getAttr name pkgs));

    in

    derivation ({
      inherit (drvInfo) name version;
      deps = mapDeps deps;
      build-deps = mapDeps build-deps;
      inherit outputs system pkg path;

      builder = "${builder-wrapper}/bin/bbenv-build-wrapper";
      # args = [ ];

      # When this is set, Nix writes the environment to a JSON file at
      # $NIX_BUILD_TOP/.attrs.json. This approach is generally cleaner than
      # parsing environment variables as strings.
      __structuredAttrs = true;

    } // extraAttrs);
}
