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
, compile ? true
, java-opts ? [ ]

  # Custom utils
, clj-builder
}:
let

  fullId = if (lib.strings.hasInfix "/" name) then name else "${name}/${name}";
  groupId = builtins.head (lib.strings.splitString "/" fullId);
  artifactId = builtins.elemAt (lib.strings.splitString "/" fullId) 1;

  utils = import ./utils.nix;

  classpath = utils.classpath-string {
    inherit fetchurl fetchgit;
    lock =
      if (builtins.isString lock-file) then
        "${projectSrc}/${lock-file}"
      else
        lock-file;
  };

  javaMain = builtins.replaceStrings [ "-" ] [ "_" ] main-ns;

  template =
    if compile then
      ''
        #!${runtimeShell}

        exec "${jdkRunner}/bin/java" \
            -classpath "@classpath@" ${javaMain} "$@"
      ''
    else
      ''
        #!${runtimeShell}

        exec "${jdkRunner}/bin/java" \
            -classpath "@classpath@" clojure.main -m ${javaMain} "$@"
      '';
in

stdenv.mkDerivation {
  inherit version classpath template compile;
  passAsFile = [ "template" ];

  pname = lib.strings.sanitizeDerivationName artifactId;
  src = projectSrc;

  nativeBuildInputs = [ jdk ];

  outputs = [ "out" "dev" "lib" ];

  passthru = {
    inherit main-ns fullId groupId artifactId javaMain;
  };

  buildPhase =
    ''
      runHook preBuild
    '' +
    (
      if compile then
        let
          cp-cmd = lib.strings.optionalString
            (lock-file != default-lock-file)
            "cp ${lock-file} ./${default-lock-file}";
        in
        ''
          ${cp-cmd}
          jarPath=$(${clj-builder} core/jar ":project-dir" '"'"$(pwd)"'"' \
            ":lib-name" '"'"${fullId}"'"' \
            ":version" '"'"${version}"'"' \
            ":java-opts" '[ ${lib.concatMapStringsSep " " lib.strings.escapeNixString java-opts} ]' \
            ":main-ns" '"'"${main-ns}"'"' )
        ''
      else
        ''
          paths=$(${clj-builder} core/paths ":deps" '"'"deps.edn"'"')

          for p in $paths; do
            addToSearchPath classpath "${projectSrc}/$p"
          done
        ''
    ) +
    ''
      runHook postBuild
    ''
  ;

  installPhase =
    ''
      runHook preInstall

      mkdir -p $lib
    ''
    +
    (if compile then
      ''
        cp $jarPath $lib
        export classpath="$lib/$(basename $jarPath):''${classpath}"
      ''
    else "") +
    ''
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
