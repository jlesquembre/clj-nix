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
  inherit version;
  pname = name;

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

  # For the options, see:
  # https://www.graalvm.org/22.1/reference-manual/native-image/BuildConfiguration/
  # https://www.graalvm.org/22.1/reference-manual/native-image/Options/
  # Option order is important
  buildPhase =
    ''
      export LC_ALL="en_US.UTF-8"

      runHook preBuild

      export jarPath=$(cat ${cljDrv}/nix-support/jar-path)
      native-image ''${nativeImageBuildArgs[@]} -jar "$jarPath" ${name}

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
          export jarPath=$(cat ${cljDrv}/nix-support/jar-path)
          ${graalvm}/bin/java \
            -agentlib:native-image-agent=caller-filter-file=${filter-json},config-output-dir=${outDir} \
            -cp "${graal-build-time}" \
            -jar "$jarPath" \
            ${cljDrv.javaMain} "$@"
        '';
    };
} // extra-attrs)
