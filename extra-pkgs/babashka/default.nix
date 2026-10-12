{ lib
, mkCljBin
, mkGraalBin
, fetchFromGitHub
, fetchurl
, rlwrap
, makeWrapper
, writeShellApplication
, graalvmPackages
, nix-package-updater
, srcFromJson
, writeScriptBin
, writers
, deps-lock
, coreutils
, git
, jq
}:

{ graalvm ? graalvmPackages.graalvm-ce
, withFeatures ? [ ]
, bbLean ? false
, wrap ? true
}:
let
  # See
  # https://github.com/babashka/babashka/blob/master/doc/build.md#feature-flags
  bb-feature-list = [
    "CSV"
    "JAVA_NET_HTTP"
    "JAVA_NIO"
    "JAVA_TIME"
    "TRANSIT"
    "XML"
    "YAML"
    "HTTPKIT_CLIENT"
    "HTTPKIT_SERVER"
    "CORE_MATCH"
    "HICCUP"
    "TEST_CHECK"
    "SPEC_ALPHA"
    "JDBC"
    "SQLITE"
    "POSTGRESQL"
    "HSQLDB"
    "ORACLEDB"
    "DATASCRIPT"
    "LANTERNA"
    "LOGGING"
    "PRIORITY_MAP"
  ];

  features = map lib.strings.toUpper withFeatures;
  invalid-features = lib.lists.subtractLists bb-feature-list features;

in

assert
(
  lib.assertMsg
    ((builtins.length invalid-features) == 0)
    ''
      Invalid babashka features: ${lib.strings.concatStringsSep ", " invalid-features}
    ''
);

let
  feature-exports =
    lib.strings.concatStringsSep
      "\n"
      (map (f: ''export BABASHKA_FEATURE_${f}="true"'') features);

  projectInfo = srcFromJson ./src.json;

  babashka-unwrapped =
    mkCljBin {
      inherit (projectInfo) version;
      projectSrc = projectInfo.src;
      lockfile = ./deps-lock.json;
      enableLeiningen = true;

      name = "babashka/babashka";
      main-ns = "babashka.main";
      jdkRunner = graalvm;
      buildCommand =
        ''
          ${if bbLean then "export BABASHKA_LEAN=true" else ""}
          ${feature-exports}
          bash script/uberjar

          export GRAALVM_HOME="${graalvm}"

          # Babashka < 1.13 depends on graal-build-time 0.1.0, whose
          # InitAtBuildTimeFeature is registered only through the
          # @AutomaticFeature annotation. GraalVM 25.1 removed @AutomaticFeature
          # (oracle/graal GR-73556), so the feature silently stops running, the
          # Clojure classes are no longer initialized at build time, and the
          # resulting binary fails at startup with "Could not locate
          # clojure/core__init.class". Enable the feature explicitly when the
          # uberjar still ships it. Newer babashka versions use
          # graal-build-time >= 1.0.5, which is enabled through
          # native-image.properties instead.
          compile_args=()
          BABASHKA_JAR="target/babashka-$(cat resources/BABASHKA_VERSION)-standalone.jar"
          if "${graalvm}/bin/jar" tf "$BABASHKA_JAR" | grep -qx 'InitAtBuildTimeFeature.class'; then
            compile_args+=("--features=InitAtBuildTimeFeature")
          fi
          bash script/compile "''${compile_args[@]}"
        '';

      outputs = [ "out" ];
      installPhase =
        ''
          mkdir -p $out/bin
          cp bb $out/bin
        '';

      passthru.updateScript = writers.writeBashBin "update-babashka"
        {
          makeWrapperArgs = let bin-path = lib.makeBinPath [ jq coreutils git deps-lock ]; in [
            "--prefix"
            "PATH"
            ":"
            "${bin-path}"
          ];
        }
        ''
          ${nix-package-updater} extra-pkgs/babashka/src.json
          VERSION=$(jq -r '.version' extra-pkgs/babashka/src.json)
          TMP_DIR=$(mktemp -d)
          git clone --depth 1 --branch "v$VERSION" --recursive https://github.com/babashka/babashka "$TMP_DIR/bb"
          cd "$TMP_DIR/bb"
          deps-lock --lein --deps-exclude resources/META-INF/babashka/deps.edn
          cd -
          cp "$TMP_DIR/bb/deps-lock.json" extra-pkgs/babashka/deps-lock.json
        '';
    };
in
if wrap then
  writeShellApplication
  {
    name = "bb";
    runtimeInputs = [ babashka-unwrapped rlwrap ];
    text = ''
      rlwrap bb "$@"
    '';
  }
else
  babashka-unwrapped
