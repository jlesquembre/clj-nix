{ fetchurl
, fetchgit
, jdk
, jq
, runtimeShell
, runCommand
, leiningen
, writeShellApplication
}:
let

  lock = builtins.fromJSON (builtins.readFile ./builder-lock.json);

  deps-classpath =
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
    { name, runtimeInputs, clj-main, classpath }:

    writeShellApplication {
      inherit name runtimeInputs;
      text = ''
        exec "${jdk}/bin/java" \
            "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory" \
            "-classpath" "${classpath}" clojure.main -m ${clj-main} "$@"
      '';
    };

in
{
  clj-builder = writeCljApplication {
    name = "clj-builder";
    runtimeInputs = [ leiningen ];
    clj-main = "cljnix.builder-cli";
    classpath = "${../src}:${deps-classpath}";
  };

  deps-lock = writeCljApplication {
    name = "deps-lock";
    runtimeInputs = [ jq leiningen ];
    clj-main = "cljnix.core";
    classpath = "${../src}:${deps-classpath}";
  };
}
