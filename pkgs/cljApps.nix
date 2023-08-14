{ fetchurl
, fetchgit
, lib
  # , jdk
, clojure
, jq
, runtimeShell
, runCommand
, leiningen
, writeShellApplication
, mk-deps-cache
}:
let

  # lock = builtins.fromJSON (builtins.readFile ./builder-lock.json);

  # deps-classpath =
  #   let
  #     deps = builtins.concatMap
  #       (dep:
  #         if !(dep ? rev) then # maven dep
  #           let src = fetchurl { inherit (dep) url hash; }; in
  #           [ "${src}" ]
  #         else # git dep
  #           let src = fetchgit { inherit (dep) url hash rev; }; in
  #           builtins.map (x: "${src}/${x}") dep.paths
  #       )
  #       lock;
  #   in
  #   builtins.concatStringsSep ":" deps;


  deps-cache = mk-deps-cache {
    # lockfile = builtins.fromJSON (builtins.readFile ./builder-lock.json);
    lockfile = ./builder-lock.json;
    clojure-paths = [ "${../src}" ];
    clojure-project = ../.;
    # inherit maven-extra;
  };
  writeCljApplication =
    { name, runtimeInputs, clj-main }:

    writeShellApplication {
      inherit name runtimeInputs;
      text = ''
        export CLJ_CONFIG=${deps-cache}/.clojure
        export CLJ_CACHE=$TMP/.clj_cache
        # export CLJ_CACHE=${deps-cache}/cp-cache
        export GITLIBS="${deps-cache}/.gitlibs"
        cd ${deps-cache}/code
        clojure -M -m ${clj-main} "$@"
        # clojure -M cljnix/builder_cli.clj "$@"

      '';
      # exec "${jdk}/bin/java" \
      #     "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory" \
      #     "-classpath" "${classpath}" clojure.main -m ${clj-main} "$@"
    };

in
{
  clj-builder = writeCljApplication {
    name = "clj-builder";
    runtimeInputs = [ clojure leiningen ];
    clj-main = "cljnix.builder-cli";
    # classpath = "${../src}:${deps-classpath}";
  };

  deps-lock = writeCljApplication {
    name = "deps-lock";
    runtimeInputs = [ clojure jq leiningen ];
    clj-main = "cljnix.core";
    # classpath = "${../src}:${deps-classpath}";
  };
}
