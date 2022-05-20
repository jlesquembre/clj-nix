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
