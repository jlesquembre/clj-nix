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
, compileCljOpts ? null
, javacOpts ? null

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
    "compileCljOpts"
    "javacOpts"
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
  javaOpts = lib.concatStringsSep " " java-opts;

  template =
    ''
      #!${runtimeShell}

      exec "${jdkRunner}/bin/java" ${javaOpts} \
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
        clj-builder
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
      clj-builder patch-git-sha "$(pwd)"
      runHook postPatch
    '';

  # Clojure environment variables:
  # https://clojure.org/reference/deps_and_cli#_clojure_cli_usage

  # CLJ_CONFIG:
  # https://github.com/clojure/tools.deps/blob/be30a1ae275eabfd1eba571080e039451c122c69/src/main/clojure/clojure/tools/deps.clj#L107-L117

  # GITLIBS:
  # https://github.com/clojure/tools.gitlibs/blob/f6544f4dab32c5d5f1610d6c2a5a256d23226821/src/main/clojure/clojure/tools/gitlibs/config.clj#L30-L35

  # CLJ_CACHE
  # https://github.com/clojure/brew-install/blob/271c2c5dd45ed87eccf7e7844b079355297d0974/src/main/resources/clojure/install/clojure#L295-L301
  buildPhase =
    ''
      runHook preBuild

      export HOME="${deps-cache}"
      export JAVA_TOOL_OPTIONS="-Duser.home=${deps-cache}"

      export CLJ_CONFIG="$HOME/.clojure"
      export CLJ_CACHE="$TMP/cp_cache"
      export GITLIBS="$HOME/.gitlibs"

      export LEIN_OFFLINE=true
      export LEIN_JVM_OPTS="-Dmaven.repo.local=${deps-cache}/.m2 -Duser.home=${deps-cache}"
      export LEIN_HOME=.lein
    ''
    +
    (
      if builtins.isNull buildCommand then
        ''
          clj-builder uber "${fullId}" "${version}" "${main-ns}" \
            '${builtins.toJSON compileCljOpts}' \
            '${builtins.toJSON javacOpts}'
        ''

      # Don't check for :gen-class with custom build commands
      # Our assumption about the :paths could be wrong, some projects can use
      # :extra-paths in an alias
      else
        ''
          # clj-builder check-main "${fullId}" "${version}" "${main-ns}"
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
