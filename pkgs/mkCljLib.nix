{ stdenv
, lib
, clojure

  # Used by clj tools.build to compile the code
, jdk

  # Custom utils
, clj-builder
, mk-deps-cache
}:

{
  # User options
  projectSrc
, name
, version ? "DEV"
, buildCommand ? null
, maven-extra ? [ ]
, ...
}@attrs:

let

  extra-attrs = builtins.removeAttrs attrs [
    "projectSrc"
    "name"
    "version"
    "buildCommand"
    "maven-extra"
    "nativeBuildInputs"
  ];

  deps-cache = mk-deps-cache {
    lockfile = (projectSrc + "/deps-lock.json");
    inherit maven-extra;
  };

  fullId = if (lib.strings.hasInfix "/" name) then name else "${name}/${name}";
  groupId = builtins.head (lib.strings.splitString "/" fullId);
  artifactId = builtins.elemAt (lib.strings.splitString "/" fullId) 1;

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
        jdk
        clojure
      ];

  passthru = {
    inherit fullId groupId artifactId;
  };

  patchPhase =
    ''
      runHook prePatch
      ${clj-builder}/bin/clj-builder patch-git-sha "$(pwd)"
      runHook postPatch
    '';

  buildPhase =
    ''
      runHook preBuild

      export HOME="$TMP/clj-home"
      mkdir -p "$HOME"
      cp -rL "${deps-cache}/." "$HOME/"
      chmod -R u+w "$HOME"

      export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
      export CLJ_CONFIG="$HOME/.clojure"
      export CLJ_CACHE="$TMP/cp_cache"
      export GITLIBS="$HOME/.gitlibs"
    ''
    +
    (
      if builtins.isNull buildCommand then
        ''
          ${clj-builder}/bin/clj-builder jar "${fullId}" "${version}"
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

      mkdir -p $out

      jarPath="$(find target -type f -name "*.jar" -print | head -n 1)"
      cp $jarPath $out

      runHook postInstall
    '';
} // extra-attrs)
