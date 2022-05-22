{ lib, fetchurl, fetchgit, jdk, runtimeShell, runCommand, clojure }:
let

  lock = builtins.fromJSON (builtins.readFile ./builder-lock.json);

  classpath =
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


  template =
    ''
      #!${runtimeShell}
      exec "${jdk}/bin/java" \
          "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory" \
          "-classpath" "@cp@" clojure.main -m cljnix.core "$@"
    '';

in
runCommand "cljBuilder"
{
  inherit template;
  passAsFile = [ "template" ];
  cp = "${../src}:${classpath}";
}
  ''
    substitute $templatePath $out \
      --subst-var cp
    chmod +x $out
  ''
