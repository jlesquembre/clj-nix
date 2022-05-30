# Adapted from
# https://github.com/NixOS/nixpkgs/blob/master/pkgs/build-support/build-graalvm-native-image/default.nix

{ lib
, stdenv
, fetchurl
, graalvmCEPackages
, glibcLocales
, writeShellScript
, writeText
}:

{ cljDrv
, name ? cljDrv.pname
, version ? cljDrv.version
, graalvm ? graalvmCEPackages.graalvm17-ce

, nativeBuildInputs ? [ ]

  # Default native-image arguments. You probably don't want to set this,
  # except in special cases. In most cases, use extraNativeBuildArgs instead
, nativeImageBuildArgs ? [
    "-H:CLibraryPath=${lib.getLib graalvm}/lib"
    (lib.optionalString stdenv.isDarwin "-H:-CheckToolchain")
    "-H:Name=${name}"
    "-H:+ReportExceptionStackTraces"
    # "-H:+PrintClassInitialization"
    # "--initialize-at-build-time"
    "--no-fallback"
    "--verbose"
  ]

  # Extra arguments to be passed to the native-image
, extraNativeImageBuildArgs ? [ ]

  # XMX size of GraalVM during build
, graalvmXmx ? "-J-Xmx6g"
, ...
}@attrs:

let
  extra-attrs = builtins.removeAttrs attrs [
    "cljDrv"
    "name"
    "version"
    "graalvm"
    "nativeBuildInputs"
    "nativeImageBuildArgs"
    "extraNativeImageBuildArgs"
    "graalvmXmx"
  ];

  graal-build-time = fetchurl {
    url = "https://repo.clojars.org/com/github/clj-easy/graal-build-time/0.1.4/graal-build-time-0.1.4.jar";
    hash = "sha256-LxsgDKwg1tfioJlny6yrxX76svCLrZsetPAgXP30+hU=";
  };

in

assert
(
  lib.assertMsg
    (lib.strings.hasInfix "." cljDrv.main-ns)
    ''
      Single segment namespaces not supported: ${cljDrv.main-ns}
    ''
);

stdenv.mkDerivation ({
  inherit name version;

  dontUnpack = true;

  nativeBuildInputs = nativeBuildInputs ++ [ graalvm glibcLocales ];

  nativeImageBuildArgs =
    [
      "-classpath"
      "${graal-build-time}"
    ] ++
    nativeImageBuildArgs ++
    extraNativeImageBuildArgs ++
    [
      graalvmXmx
    ];

  buildPhase =
    ''
      export LC_ALL="en_US.UTF-8"

      runHook preBuild

      jar="$(find ${cljDrv.lib} -type f   -name "*.jar" -print | head -n 1)"
      native-image ''${nativeImageBuildArgs[@]} -jar "$jar"

      runHook postBuild
    '';

  installPhase =
    ''
      runHook preInstall

      install -Dm755 ${name} -t $out/bin

      runHook postInstall
    '';

  passthru =

    let
      # See
      # https://github.com/clj-easy/graal-docs#reflection
      filter-json = writeText "filter.json"
        ''
          {
            "rules": [
              { "excludeClasses": "clojure.**" },
              { "includeClasses": "clojure.lang.Reflector" }
            ]
          }
        '';

      outDir = "./resources/META-INF/native-image-new/${cljDrv.fullId}";
    in

    {
      # See https://www.graalvm.org/22.0/reference-manual/native-image/BuildConfiguration/
      agentlib = writeShellScript "agentlib-helper.sh"
        ''
          jar="$(find ${cljDrv.lib} -type f   -name "*.jar" -print | head -n 1)"
          ${graalvm}/bin/java \
            -agentlib:native-image-agent=caller-filter-file=${filter-json},config-output-dir=${outDir} \
            -cp "${graal-build-time}" \
            -jar "$jar" \
            ${cljDrv.javaMain} "$@"
        '';
    };
} // extra-attrs)
