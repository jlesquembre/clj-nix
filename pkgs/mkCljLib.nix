{
  stdenv,
  lib,
  clojure,

  # Used by clj tools.build to compile the code
  jdk,

  # Custom utils
  clj-builder,
  mk-deps-cache,
  fake-git,
}:

{
  # User options
  projectSrc,
  name,
  version ? "DEV",
  buildCommand ? null,
  maven-extra ? [ ],
  ...
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
stdenv.mkDerivation (
  {
    inherit version;

    pname = lib.strings.sanitizeDerivationName artifactId;
    src = projectSrc;

    # Build time deps
    nativeBuildInputs = attrs.nativeBuildInputs or [ ] ++ [
      fake-git
      jdk
      clojure
    ];

    passthru = {
      inherit
        deps-cache
        fullId
        groupId
        artifactId
        ;
    };

    patchPhase = ''
      runHook prePatch
      ${clj-builder}/bin/clj-builder patch-git-sha "$(pwd)"
      runHook postPatch
    '';

    buildPhase = ''
      runHook preBuild

      export HOME="${deps-cache}"
      export JAVA_TOOL_OPTIONS="-Duser.home=${deps-cache}"
    ''
    + (
      if builtins.isNull buildCommand then
        ''
          ${clj-builder}/bin/clj-builder jar "${fullId}" "${version}"
        ''
      else
        ''
          ${buildCommand}
        ''
    )
    + ''
      runHook postBuild
    '';

    installPhase = ''
      runHook preInstall

      mkdir -p $out

      # Sort, because `find` lists files in directory order, which differs
      # between filesystems.
      jarPath="$(find target -type f -name "*.jar" -print | sort | head -n 1)"
      cp $jarPath $out

      runHook postInstall
    '';
  }
  // extra-attrs
)
