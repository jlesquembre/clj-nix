{ lib
, mkCljBin
, mkGraalBin
, fetchFromGitHub
, fetchurl
, rlwrap
, makeWrapper
, writeShellApplication
, graalvmCEPackages
, nix-package-updater
, srcFromJson
, writeScriptBin
, writers
, deps-lock
, coreutils
, git
, jq
}:

{ graalvm ? graalvmCEPackages.graalvm-ce
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

      patches = [ ./0001-Update-build-options-for-graal-23.patch ];

      name = "babashka/babashka";
      main-ns = "babashka.main";
      jdkRunner = graalvm;
      buildCommand =
        ''
          ${if bbLean then "export BABASHKA_LEAN=true" else ""}
          ${feature-exports}
          bash script/uberjar

          export GRAALVM_HOME="${graalvm}"
          bash script/compile
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
