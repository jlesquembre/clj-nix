{ stdenv
, lib
, runtimeShell
, jdk17_headless
, cljHooks
}:

{ jdkBase ? jdk17_headless
, name ? "customJDK"
, version ? "DEV"
, cljDrv ? null
  # JDK modules options
, jdkModules ? null
, extraJdkModules ? [ ]
, locales ? null
, ...
}@attrs:

let

  extra-attrs = builtins.removeAttrs attrs [
    "jdkBase"
    "name"
    "version"
    "cljDrv"
  ];

  jarPath = lib.fileContents "${cljDrv}/nix-support/jar-path";

in
stdenv.mkDerivation ({
  inherit jarPath;
  name = if cljDrv == null then name else cljDrv.pname;
  version = if cljDrv == null then version else cljDrv.version;

  stripDebugFlags = [ "--strip-unneeded" ];
  nativeBuildInputs = [
    jdkBase
    cljHooks.customJdkInstallHook
  ];

  outputs =
    if cljDrv == null then
      [ "out" ]
    else
      [ "out" "jdk" ];

  dontUnpack = true;

  passthru = if cljDrv == null then { } else
  {
    inherit jarPath;
    inherit (cljDrv) main-ns fullId groupId artifactId javaMain;
  };

} // extra-attrs)
