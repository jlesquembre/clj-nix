{ stdenv
, lib
, runtimeShell
, clojure
, leiningen

  # Used by clj tools.build to compile the code
, jdk

  # Custom utils
, clj-builder
, mk-deps-cache

}:

{
  # User options
  jdkRunner ? jdk # Runtime jdk
, projectSrc
, name
, version ? "DEV"
, main-ns
, java-opts ? [ ]
, buildCommand ? null

  # Needed for version ranges
  # TODO maybe we can find a better solution?
, maven-extra ? [ ]
, ...
}@attrs:

let

  extra-attrs = builtins.removeAttrs attrs [
    "jdkRunner"
    "projectSrc"
    "name"
    "version"
    "main-ns"
    "java-opts"
    "buildCommand"
    "maven-extra"
    "nativeBuildInputs"
  ];

  deps-cache = mk-deps-cache {
    lockfile = attrs.lockfile or (projectSrc + "/deps-lock.json");
    inherit maven-extra;
  };

  fullId = if (lib.strings.hasInfix "/" name) then name else "${name}/${name}";
  groupId = builtins.head (lib.strings.splitString "/" fullId);
  artifactId = builtins.elemAt (lib.strings.splitString "/" fullId) 1;

  asCljVector = list: lib.concatMapStringsSep " " lib.strings.escapeNixString list;

  javaMain = builtins.replaceStrings [ "-" ] [ "_" ] main-ns;

  template =
    ''
      #!${runtimeShell}

      exec "${jdkRunner}/bin/java" \
          -jar "@jar@" "$@"
    '';
in
stdenv.mkDerivation ({
  inherit version template;
  passAsFile = [ "template" ];

  pname = lib.strings.sanitizeDerivationName artifactId;
  src = projectSrc;

  # Build time deps
  nativeBuildInputs =
    attrs.nativeBuildInputs or [ ]
      ++
      [
        jdk
        clojure
        leiningen
      ];

  outputs = [ "out" "lib" ];

  passthru = {
    inherit main-ns fullId groupId artifactId javaMain;
  };

  patchPhase =
    ''
      runHook prePatch
    ''
    +
    (lib.strings.optionalString (builtins.hasAttr "lockfile" attrs)
      ''
        cp "${attrs.lockfile}" deps-lock.json
      ''
    )
    +
    ''
      ${clj-builder} --patch-git-sha "$(pwd)"
      runHook postPatch
    '';

  buildPhase =
    ''
      runHook preBuild
      
      export HOME="${deps-cache}"
      export JAVA_TOOL_OPTIONS="-Duser.home=${deps-cache}"

      export LEIN_OFFLINE=true
      export LEIN_JVM_OPTS="-Dmaven.repo.local=${deps-cache}/.m2 -Duser.home=${deps-cache}"
      export LEIN_HOME=.lein

      ${clj-builder} --check-main "${fullId}" "${version}" "${main-ns}"
    ''
    +
    (
      if builtins.isNull buildCommand then
        ''
          ${clj-builder} --uber "${fullId}" "${version}" "${main-ns}"
        ''
      else
        ''
          ${buildCommand}
        ''
    )
    +
    ''
      runHook postBuild
    '';

  installPhase =
    ''
      runHook preInstall

      mkdir -p $lib
      mkdir -p $out/bin
      mkdir -p $out/nix-support

      # jarPath variable could be defined in the preInstall hook, don't override it
      if [ -z ''${jarPath+x} ]; then
        jarPath="$(find target -type f -name "*.jar" -print | head -n 1)"
      fi

      cp $jarPath $lib
      jarPath=$(basename $jarPath)
      echo "$lib/$jarPath" > $out/nix-support/jar-path

      cljBinary="$out/bin/${artifactId}"
      substitute $templatePath "$cljBinary" \
        --subst-var-by jar "$lib/$jarPath"
      chmod +x "$cljBinary"

      runHook postInstall
    '';
} // extra-attrs)
