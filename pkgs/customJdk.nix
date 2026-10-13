{ stdenv
, lib
, runtimeShell
, jdk_headless
, cljHooks
}:

{ jdkBase ? jdk_headless
, name ? "customJDK"
, version ? "DEV"
, cljDrv ? null
  # JDK modules options
, jdkModules ? null
, extraJdkModules ? [ ]
, locales ? null
, java-opts ? [ ]
, ...
}@attrs:

let

  extra-attrs = builtins.removeAttrs attrs [
    "jdkBase"
    "name"
    "version"
    "cljDrv"
    "java-opts"
    "meta"
  ];

  jarPath = lib.fileContents "${cljDrv}/nix-support/jar-path";

in
stdenv.mkDerivation ({
  inherit jarPath;
  pname = if cljDrv == null then name else cljDrv.pname;
  version = if cljDrv == null then version else cljDrv.version;

  stripDebugFlags = [ "--strip-unneeded" ];
  nativeBuildInputs = [
    jdkBase
    cljHooks.customJdkInstallHook
  ];

  javaOpts = lib.escapeShellArgs java-opts;

  outputs =
    if cljDrv == null then
      [ "out" ]
    else
      [ "out" "jdk" ];

  dontUnpack = true;

  # The install hook writes bin/<pname> for a cljDrv, else a bare JDK
  meta = { mainProgram = if cljDrv == null then "java" else cljDrv.pname; }
    // (attrs.meta or { });

  passthru = if cljDrv == null then { } else
  {
    inherit jarPath;
    inherit (cljDrv) main-ns fullId groupId artifactId javaMain;
  };

} // extra-attrs)
