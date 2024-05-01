{ stdenv
, lib
, runtimeShell
, clojure
, leiningen
, writeText

  # Default JDK.
  # Needed by clj tools.build to compile the code
, jdk

  # Custom utils
, clj-builder
, mk-deps-cache
, common
, fake-git

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
, lockfile ? null
, compileCljOpts ? null
, javacOpts ? null
, enableLeiningen ? false
, builder-extra-inputs ? [ ]
, builder-java-opts ? [ ]
, builder-preBuild ? ""
, builder-postBuild ? ""

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
    "builder-extra-inputs"
    "builder-java-opts"
    "builder-preBuild"
    "builder-postBuild"
  ];

  deps-cache = mk-deps-cache {
    lockfile = if isNull lockfile then (projectSrc + "/deps-lock.json") else lockfile;
    inherit maven-extra;
  };

  fullId = if (lib.strings.hasInfix "/" name) then name else "${name}/${name}";
  groupId = builtins.head (lib.strings.splitString "/" fullId);
  artifactId = builtins.elemAt (lib.strings.splitString "/" fullId) 1;

  asCljVector = list: lib.concatMapStringsSep " " lib.strings.escapeNixString list;

  javaMain = builtins.replaceStrings [ "-" ] [ "_" ] main-ns;

in
stdenv.mkDerivation ({
  inherit version;

  pname = lib.strings.sanitizeDerivationName artifactId;
  src = projectSrc;

  # Build time deps
  nativeBuildInputs =
    attrs.nativeBuildInputs or [ ]
      ++
      [
        jdkRunner
        (clj-builder.override {
          jdk = jdkRunner;
          extra-runtime-inputs = builder-extra-inputs;
          java-opts = builder-java-opts;
          preBuild = builder-preBuild;
          postBuild = builder-postBuild;
        })
      ]
      ++ [ fake-git ]
      ++ (lib.lists.optional (! isNull buildCommand) (clojure.override { jdk = jdkRunner; }))
      ++ (lib.lists.optional enableLeiningen leiningen);

  outputs = [ "out" "lib" ];

  javaOpts = lib.escapeShellArgs java-opts;

  passthru = {
    inherit main-ns fullId groupId artifactId javaMain;
  };

  preBuildPhases = [ "preBuildPhase" ];
  preBuildPhase =
    (lib.strings.optionalString (! isNull lockfile)
      ''
        cp "${lockfile}" deps-lock.json
      ''
    ) +
    ''
      clj-builder patch-git-sha "$(pwd)"
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

      binaryPath="$out/bin/${artifactId}"

      substitute ${common.binaryTemplate} "$binaryPath" \
        --subst-var-by jar "$lib/$jarPath" \
        --subst-var-by jdk "${jdkRunner}" \
        --subst-var javaOpts

      chmod +x "$binaryPath"

      runHook postInstall
    '';
} // extra-attrs)
