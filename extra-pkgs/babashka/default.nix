{ lib
, mkCljBin
, mkGraalBin
, fetchFromGitHub
, rlwrap
, makeWrapper
, writeShellApplication
, graalvmCEPackages
}:

{ graalvm ? graalvmCEPackages.graalvm11-ce
, withFeatures ? [ ]
, bbLean ? false
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

  version = "1.1-jdk19-loom";

  babashka =
    mkCljBin {
      inherit version;
      projectSrc = fetchFromGitHub {
        owner = "babashka";
        repo = "babashka";
        rev = "3701dc03d79df3d580b9c42b39c0d24668df154b"; ## jdk19-loom
        hash = "sha256-Wdzg5xo1DTngBlUG74cwf9GxRO7T74Lbx0sHkvLB9jg=";
        fetchSubmodules = true;
      };
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
    };
in
writeShellApplication {
  name = "bb";

  runtimeInputs = [ babashka rlwrap ];

  text = ''
    rlwrap bb "$@"
  '';
}
