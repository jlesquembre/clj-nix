{ lib
, mkCljBin
, mkGraalBin
, fetchFromGitHub
, rlwrap
, makeWrapper
, writeShellApplication
, graalvmCEPackages
, nix-package-updater
, srcFromJson
, writeScriptBin
}:

{ graalvm ? graalvmCEPackages.graalvm19-ce
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

      name = "babashka/babashka";
      main-ns = "babashka.main";
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

      passthru.updateScript = writeScriptBin "update-babashka"
        ''
          ${nix-package-updater} extra-pkgs/babashka/src.json
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
