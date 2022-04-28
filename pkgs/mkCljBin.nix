let default-lock-file = "deps-lock.json"; in

{ stdenv
, lib
, callPackage
, fetchurl
, fetchgit
, writeShellScript
, writeText
, runCommand
, runtimeShell
, gnused

  # Used by clj tools.build to compile the code
, jdk


  # User options

  # Runtime jdk
, jdkRunner ? jdk
, projectSrc
, name
, version ? "DEV"
, main-ns
, lock-file ? default-lock-file
, ns-compile ? ""
, ns-compile-extra ? [ ]
, java-opts ? [ ]
, aliases ? [ ]

  # Custom utils
, clj-builder
}:
let

  fullId = if (lib.strings.hasInfix "/" name) then name else "${name}/${name}";
  groupId = builtins.head (lib.strings.splitString "/" fullId);
  artifactId = builtins.elemAt (lib.strings.splitString "/" fullId) 1;

  utils = import ./utils.nix;

  asCljVector = list: lib.concatMapStringsSep " " lib.strings.escapeNixString list;

  javaMain = builtins.replaceStrings [ "-" ] [ "_" ] main-ns;

  template =
    ''
      #!${runtimeShell}

      exec "${jdkRunner}/bin/java" \
          -classpath "@classpath@" ${javaMain} "$@"
    '';
  clj-classpath =
    stdenv.mkDerivation {
      src = projectSrc;
      name = "clj-classpath";
      dontInstall = true;
      buildPhase =
        ''
          ${clj-builder} core/classpath-prn \
            ":project-dir" '"'"$(pwd)"'"' \
            ":aliases" '[ ${asCljVector aliases} ]' > $out
        '';

    };

  depsDrvs = utils.cljDeps {
    inherit fetchurl fetchgit lib;
    computedClasspath = builtins.readFile "${clj-classpath}";
    lock = "${projectSrc}/deps-lock.json";
  };

in

stdenv.mkDerivation {
  inherit version template;
  passAsFile = [ "template" ];

  pname = lib.strings.sanitizeDerivationName artifactId;
  src = projectSrc;

  # Build time deps
  nativeBuildInputs = [ jdk gnused ];

  outputs = [ "out" "dev" "lib" ];

  passthru = {
    inherit main-ns fullId groupId artifactId javaMain;
  };

  # TODO find a better way to propagate the runtime dependecies
  buildPhase =
    ''
      runHook preBuild

      echo ${builtins.concatStringsSep ":" depsDrvs} > /dev/null

      buildClasspath=$(sed "1q;d" ${clj-classpath})
      classpath=$(sed "2q;d" ${clj-classpath})


      jarPath=$(${clj-builder} core/jar \
        ":project-dir" '"'"$(pwd)"'"' \
        ":lib-name" '"'"${fullId}"'"' \
        ":version" '"'"${version}"'"' \
        ":ns-compile" '"'"${ns-compile}"'"' \
        ":ns-compile-extra" '[ ${asCljVector ns-compile-extra} ]' \
        ":aliases" '[ ${asCljVector aliases} ]' \
        ":java-opts" '[ ${asCljVector java-opts} ]' \
        ":main-ns" '"'"${main-ns}"'"' )

      runHook postBuild
    ''
  ;

  installPhase =
    ''
      runHook preInstall

      mkdir -p $lib
      cp $jarPath $lib
      export classpath="$lib/$(basename $jarPath):''${classpath}"

      mkdir -p $out/bin
      binary="$out/bin/${artifactId}"
      substitute $templatePath "$binary" \
        --subst-var classpath
      chmod +x "$binary"

      mkdir -p $dev
      echo "$classpath"  > $dev/classpath

      runHook postInstall
    '';
}
