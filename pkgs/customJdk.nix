{ stdenv
, lib
, callPackage
, fetchurl
, fetchgit
, writeShellScript
, writeText
, runCommand
, runtimeShell

, jdk17_headless

  # API
, jdkBase ? jdk17_headless

, name ? "customJDK"
, version ? "DEV"
, cljDrv ? null
  # Manually set the modules
, jdkModules ? null
, locales ? null
}:

let
  classpath =
    if cljDrv == null then null else
    lib.removeSuffix
      "\n"
      (builtins.readFile "${cljDrv.dev}/classpath");

  template =
    ''
      #!${runtimeShell}

      exec "@jdk@/bin/java" \
          -classpath "@classpath@" clojure.main -m @mainNs@ "$@"
    '';

in
stdenv.mkDerivation {
  inherit name version classpath locales template;

  passAsFile = [ "template" ];
  stripDebugFlags = [ "--strip-unneeded" ];
  nativeBuildInputs = [ jdkBase ];

  outputs =
    if cljDrv == null then
      [ "out" ]
    else
      [ "out" "jdk" ];

  dontUnpack = true;

  installPhase =
    ''
      runHook preInstall

      if [[ -z "$jdkModules" ]]; then
    '' +

    (if cljDrv == null then
      ''
        export jdkModules="java.base"
      ''
    else
      ''
        export jdkModules=''$(jdeps -cp "$classpath" --print-module-deps "${cljDrv.lib}/${cljDrv.name}.jar")
      '')
    +

    ''
      fi

      if [[ -n "$locales" && "$jdkModules" != *"jdk.localedata"* ]]; then
        export jdkModules="''${jdkModules},jdk.localedata"
      fi

      jlink \
        --no-header-files \
        --no-man-pages \
        --add-modules ''${jdkModules} \
        ${if locales==null then "" else ''--include-locales  ${locales}''} \
        --compress 2 \
        --output ${if classpath == null then "$out" else "$jdk"}


    ''
    +

    (if cljDrv == null then "" else
    ''
      mkdir -p $out/bin

      binary="$out/bin/${cljDrv.pname}"
      touch $binary

      substitute "$templatePath" "$binary" \
        --subst-var classpath \
        --subst-var-by jdk "$jdk" \
        --subst-var-by mainNs "${cljDrv.main-ns}"
      chmod +x "$binary"
    '')
    +

    ''
      runHook postInstall
    '';

  passthru = if cljDrv == null then { } else
  {
    main-ns = cljDrv.main-ns;
    classpath = classpath;
  };

}
