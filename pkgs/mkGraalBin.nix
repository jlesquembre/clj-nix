{ lib
, stdenv
, fetchurl
, glibcLocales
, writeShellScript
, graalvmCEPackages
, writeText
, buildGraalvmNativeImage
}:

{ cljDrv
, name ? cljDrv.pname
, version ? cljDrv.version

  # Options to buildGraalvmNativeImage (passed as provided)
  # TODO rename to graalvmDrv, align with buildGraalvmNativeImage on nixpkgs
, graalvm ? graalvmCEPackages.graalvm-ce
, meta ? { }

  # Options to buildGraalvmNativeImage
  # If empty, we don't pass those, defaults from buildGraalvmNativeImage are used
, nativeBuildInputs ? [ ]
, nativeImageBuildArgs ? [ ]
, extraNativeImageBuildArgs ? [ ]
, graalvmXmx ? ""
, ...
}@attrs:

let
  is-empty = element:
    if builtins.isList element then element == [ ]
    else if builtins.isString element then element == ""
    else false;

  # Always remove
  extra-attrs = builtins.removeAttrs attrs [
    "cljDrv"
    "name"
    "version"
    "extraNativeImageBuildArgs"
    "graalvm"
  ];

  # Remove only if empty
  other-attrs = [
    "nativeBuildInputs"
    "nativeImageBuildArgs"
    "graalvmXmx"
  ];

  extra-attrs' =
    lib.filterAttrs
      (k: v: (builtins.elem k other-attrs) && (is-empty v))
      extra-attrs;

  graal-build-time =
    let version = "1.0.5"; in
    fetchurl {
      url = "https://repo.clojars.org/com/github/clj-easy/graal-build-time/${version}/graal-build-time-${version}.jar";
      hash = "sha256-M6/U27a5n/QGuUzGmo8KphVnNa2K+LFajP5coZiFXoY=";
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

buildGraalvmNativeImage ({

  inherit version;
  pname = name;
  graalvmDrv = graalvm;
  meta.mainProgram = name;

  dontUnpack = true;
  src = lib.fileContents "${cljDrv}/nix-support/jar-path";

  extraNativeImageBuildArgs = extraNativeImageBuildArgs ++
    [
      "-classpath"
      "${graal-build-time}"
      "--features=clj_easy.graal_build_time.InitClojureClasses"
    ];
} // extra-attrs')
