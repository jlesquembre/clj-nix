{ common
, leiningen
, jdk
, extra-runtime-inputs ? [ ]
, java-opts ? [ ]
, preBuild ? ""
, postBuild ? ""
}:

common.writeCljApplication {
  name = "clj-builder";
  runtimeInputs = [ leiningen jdk ] ++ extra-runtime-inputs;
  clj-main = "cljnix.builder-cli";
  classpath = "${../src}:${common.internal-deps-classpath}";
  inherit java-opts preBuild postBuild;
}
