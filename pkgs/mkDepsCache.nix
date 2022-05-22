{ lib
, fetchurl
, fetchgit
, jdk
, runtimeShell
, runCommand
, linkFarm
, lockfile
}:
let
  consUrl = segments:
    lib.pipe
      segments
      [
        (map (lib.removeSuffix "/"))
        (map (lib.removePrefix "/"))
        (lib.concatStringsSep "/")
      ];

  lock = builtins.fromJSON (builtins.readFile lockfile);

  maven-deps =
    { mvn-path, mvn-repo, hash }:
    {

      name = mvn-path;
      path = fetchurl {
        inherit hash;
        url = consUrl [ mvn-repo mvn-path ];
      };

    };

  git-deps =
    { lib, url, rev, hash, ... }:
    {
      name = "${lib}/${rev}";
      path = fetchgit {
        inherit url rev hash;
      };
    };


  maven-cache = linkFarm "maven-cache" (builtins.map maven-deps lock.mvn-deps);

  git-cache = linkFarm "git-cache" (builtins.map git-deps lock.git-deps);

  git-repo-config = runCommand "gitlibs-config-dir"
    { }
    (
      ''
        mkdir -p $out
      '' +
      (lib.concatMapStringsSep
        "\n"
        ({ git-dir, ... }:
          ''
            mkdir -p $out/${git-dir}
            touch $out/${git-dir}/config
          ''
        )
        lock.git-deps)
    );
  dotclojure = runCommand "dotclojure"
    { }
    ''
      mkdir -p $out/tools
      echo "{}" > $out/deps.edn
      echo "{}" > $out/tools/tools.edn
    '';
in
linkFarm "clj-cache" [
  {
    name = ".m2/repository";
    path = maven-cache;
  }
  {
    name = ".gitlibs/libs";
    path = git-cache;
  }
  {
    name = ".gitlibs/_repos";
    path = git-repo-config;
  }
  {
    name = ".clojure";
    path = dotclojure;
  }
]
