let

  cljDeps =
    { lib
    , fetchurl
    , fetchgit
    , lock
    , computedClasspath
    }:
    let
      all-deps = (builtins.fromJSON (builtins.readFile lock)).nix-info;
      paths = lib.splitString ":" computedClasspath;
      required-deps = builtins.filter
        (dep:
          (lib.lists.any
            (path: lib.strings.hasPrefix
              dep.nix-path
              path)
            paths)
        )
        all-deps;


      deps = builtins.map
        (dep:
          if dep.type == "mvn" then
            (fetchurl { inherit (dep) url hash; })
          else
            (fetchgit { inherit (dep) url sha256 rev; })
        )
        required-deps;
    in
    deps;

in
{
  inherit cljDeps;

  clj-builder =
    { lib, fetchurl, fetchgit, jdk, runtimeShell, runCommand, clojure }:
    let
      template =
        ''
          #!${runtimeShell}
          exec "${jdk}/bin/java" \
              "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory" \
              "-classpath" "@cp@" clojure.main -m clojure.run.exec "$@"
        '';
      classpath = builtins.readFile ../builder.classpath;
      depsDrvs = cljDeps {
        inherit fetchurl fetchgit lib;
        computedClasspath = classpath;
        lock = ../deps-lock.json;
      };
    in
    runCommand "cljBuilder"
      {
        inherit template;
        passAsFile = [ "template" ];
        cp = "${clojure}/libexec/exec.jar:${../src}:${classpath}";
      }
      # TODO find a better way to propagate the runtime dependecies
      ''
        echo ${builtins.concatStringsSep ":" depsDrvs} > /dev/null
        substitute $templatePath $out \
          --subst-var cp
        chmod +x $out
      '';

  deps-lock = { jq, writeShellScriptBin, nix-prefetch-git, clj-builder }:
    writeShellScriptBin "deps-lock"
      ''
        export PATH=${nix-prefetch-git}/bin:$PATH
        ${clj-builder} core/deps-lock ":deps-path" '"'"''${1:-deps.edn}"'"' | ${jq}/bin/jq . > deps-lock.json
      '';

}
