{ lib
, runtimeShell
, writeText
, fetchurl
, fetchgit
, writeShellApplication
}:

let
  lock = builtins.fromJSON (builtins.readFile ./builder-lock.json);
in

{
  binaryTemplate =
    writeText "template" ''
      #!${runtimeShell}

      exec "@jdk@/bin/java" @javaOpts@ \
          -jar "@jar@" "$@"
    '';

  internal-deps-classpath =
    let
      deps = builtins.concatMap
        (dep:
          if !(dep ? rev) then # maven dep
            let src = fetchurl { inherit (dep) url hash; }; in
            [ "${src}" ]
          else # git dep
            let src = fetchgit { inherit (dep) url hash rev; }; in
            builtins.map (x: "${src}/${x}") dep.paths
        )
        lock;
    in
    builtins.concatStringsSep ":" deps;

  writeCljApplication =
    { name, runtimeInputs, clj-main, classpath, java-opts, preBuild, postBuild }:

    writeShellApplication {
      inherit name runtimeInputs;
      text =
        preBuild +
        ''
          java \
            ${lib.escapeShellArgs java-opts} \
            "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory" \
            "-classpath" "${classpath}" \
            clojure.main -m ${clj-main} "$@"
        '' +
        postBuild;
    };
}
