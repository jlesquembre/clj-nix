# clj-kondo =
#   let
#     version = "v2022.03.09";
#     cljDrv = cljpkgs.mkCljBin {
#       projectSrc = pkgs.fetchFromGitHub {
#         owner = "clj-kondo";
#         repo = "clj-kondo";
#         rev = version;
#         hash = "sha256-Yjyd48lg1VcF8pZOrEqn5g/jEmSioFRt0ETSJjp0wWU=";
#       };
#       lock-file = ./extra-pkgs/clj-kondo/deps-lock.json;

#       # https://github.com/clj-kondo/clj-kondo/blob/61d1447a56de0610c0c500fc6f6e9d6647f2262c/project.clj#L32
#       java-opts = [
#         "-Dclojure.compiler.direct-linking=true"
#         "-Dclojure.spec.skip-macros=true"
#       ];
#       name = "clj-kondo";
#       inherit version;
#       main-ns = "clj-kondo.main";
#       jdkRunner = pkgs.jdk17_headless;
#     };
#   in
#   cljpkgs.mkGraalBin {
#     inherit cljDrv;
#   };



{ mkCljBin, mkGraalBin, fetchFromGitHub }:
let
  version = "0.8.156";
  bb-jvm =
    mkCljBin {
      # projectSrc = ./.;
      inherit version;
      projectSrc = fetchFromGitHub {
        owner = "babashka";
        repo = "babashka";
        rev = "v${version}";
        hash = "sha256-83EpxPONxoGssT13cDM14Zq4J/bMGQJPXVh2uzIl1Dc=";
        fetchSubmodules = true;
      };
      lockfile = ./deps-lock.json;

      name = "babashka/babashka";
      main-ns = "babashka.main";
      # jdkRunner = pkgs.jdk17_headless;

      # buildCommand example
      # buildCommand = "clj -T:build uber";
      buildCommand =
        ''
          bash script/uberjar
        '';
      preInstall =
        ''
          jarPath="$(find target -type f -name "*babashka*.jar" -print | head -n 1)"
        '';

      # mkDerivation attributes
      # doCheck = true;
      # checkPhase = "clj -M:test";
    };
in

mkGraalBin {
  cljDrv = bb-jvm;
  name = "bb";
}
