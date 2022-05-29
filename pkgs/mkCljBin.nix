{ stdenv
, lib
, runtimeShell
, clojure

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
  ];

  deps-cache = mk-deps-cache {
    lockfile = (projectSrc + "/deps-lock.json");
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
    [
      jdk
      clojure
    ];

  outputs = [ "out" "lib" ];

  passthru = {
    inherit main-ns fullId groupId artifactId javaMain;
  };

  patchPhase =
    ''
      runHook prePatch
      ${clj-builder} --patch-git-sha "$(pwd)"
      runHook postPatch
    '';

  buildPhase =
    ''
      runHook preBuild

      export HOME="${deps-cache}"
      export JAVA_TOOL_OPTIONS="-Duser.home=${deps-cache}"
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

      jar="$(find target -type f   -name "*.jar" -print | head -n 1)"
      binary="$out/bin/${artifactId}"

      cp $jar $lib

      substitute $templatePath "$binary" \
        --subst-var-by jar "$lib/$(basename $jar)"
      chmod +x "$binary"

      runHook postInstall
    '';
} // extra-attrs)
