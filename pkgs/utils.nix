let

  toList = x: if builtins.isList x then x else [ x ];

  classpath =
    { fetchurl
    , fetchgit
    , lock
    , extraClasspaths ? [ ]
    }:
    let
      deps = builtins.concatMap
        (dep:
          if dep.type == "mvn" then
            let src = fetchurl { inherit (dep) url hash; }; in
            [ "${src}" ]
          else if dep.type == "git" then
            let src = fetchgit { inherit (dep) url sha256 rev; }; in
            builtins.map (x: "${src}/${x}") dep.paths
          else [ ]
        )
        ((builtins.fromJSON (builtins.readFile lock)).nix-info);

    in
    (toList extraClasspaths)
    ++ deps;

  classpath-string = attrs:
    builtins.concatStringsSep ":" (classpath attrs);

  clj-wrapper =
    { lib, fetchurl, fetchgit, jdk, bash, runCommand, lock, paths ? [ ], main ? "" }:

    let
      clj-wrapper-template =
        ''
          #!@bash@/bin/bash

          exec "@jdk@/bin/java" \
              "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory" \
              -classpath "@cp@" clojure.main -m @main@ "$@"
        '';
      cp = classpath-string { inherit lib fetchurl fetchgit lock paths; };
    in
    runCommand "clj-wrapper"
      {
        text = clj-wrapper-template;
        passthru.classpath = cp;
        passAsFile = [ "text" ];
        inherit jdk bash cp;
      }
      ''
        substitute $textPath $out \
          --subst-var jdk \
          --subst-var bash \
          --subst-var cp \
          --subst-var-by main ${main}
        chmod +x $out
      '';
in
{
  inherit classpath classpath-string;

  clj-builder =
    { fetchurl, fetchgit, jdk, runtimeShell, runCommand, clojure }:
    let template =
      ''
        #!${runtimeShell}

        exec "${jdk}/bin/java" \
            "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory" \
            -classpath "@cp@" clojure.main -m clojure.run.exec "$@"
      '';
    in
    runCommand "cljBuilder"
      {
        inherit template;
        passAsFile = [ "template" ];
        cp = classpath-string {
          inherit fetchurl fetchgit;
          lock = ../deps-lock.json;
          extraClasspaths = [
            "${clojure}/libexec/exec.jar"
            ../src
          ];
        };
      }
      ''
        substitute $templatePath $out \
          --subst-var cp
        chmod +x $out
          # --subst-var-by main ''${main}
      '';

  deps-lock = { jq, writeShellScriptBin, nix-prefetch-git, clj-builder }:
    writeShellScriptBin "deps-lock"
      ''
        export PATH=${nix-prefetch-git}/bin:$PATH
        ${clj-builder} core/deps-lock ":in" '"'"''${1:-deps.edn}"'"' | ${jq}/bin/jq . > deps-lock.json
      '';
}
