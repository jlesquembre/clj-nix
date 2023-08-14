{ lib
, fetchurl
, fetchgit
, clojure
  # , jdk
, runtimeShell
, runCommand
, writeText
, linkFarm
, lockfile
, maven-extra ? [ ]
, clojure-extra-paths ? [ ]
  # , git
  # , clojure-project ? null
  # , clojure-project
}:
let
  deps-lock-version = 3;

  asCljVector = list: lib.concatMapStringsSep " " lib.strings.escapeNixString list;

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
    { mvn-path, mvn-repo, hash, snapshot ? null, ... }:
    let
      path = fetchurl {
        inherit hash;
        url = consUrl [ mvn-repo mvn-path ];
      };
    in
    [
      { inherit path; name = mvn-path; }
    ]
    ++ lib.lists.optional (snapshot != null) {
      inherit path;
      name = (builtins.concatStringsSep "/" [ (builtins.dirOf mvn-path) snapshot ]);
    };

  git-deps =
    { lib, url, rev, hash, ... }:
    {
      name = "${lib}/${rev}";
      path = fetchgit {
        inherit url rev hash;
      };
    };

  maven-extra-cache = { path, content }:
    {
      name = path;
      path = writeText "maven-data" content;
    };

  maven-cache = linkFarm "maven-cache" (
    (builtins.concatMap maven-deps lock.mvn-deps)
    ++
    (builtins.map maven-extra-cache maven-extra)
  );

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
    # echo '{:mvn/local-repo "${maven-cache}" :paths [ ${asCljVector clojure-paths} ]}' > $out/deps.edn
    ''
      mkdir -p $out/tools
      echo '{:mvn/local-repo "${maven-cache}"}' > $out/deps.edn
      echo "{}" > $out/tools/tools.edn
      # echo '{:lib io.github.clojure/tools.tools :coord {:git/sha "859a6156802eaa49f2488ae087421091018586f7"}}' > $out/tools/tools.edn
    '';
  version = lock.lock-version or 0;

  cp-cache = runCommand "cp-cache"
    { nativeBuildInputs = [ clojure ]; }
    ''

      export CLJ_CONFIG=${dotclojure}

      mkdir -p $TMP/gitlibs
      export GITLIBS="$TMP/.gitlibs"
      mkdir -p $GITLIBS
      ln -s ${git-cache} $GITLIBS/libs
      ln -s ${git-repo-config} $GITLIBS/_repos

      mkdir -p $out
      export CLJ_CACHE=$out

      clojure -P
    '';
in
assert
(
  lib.assertMsg
    (version == deps-lock-version)
    ''
      Lock file generated with a different clj-nix version.
      Current version: ${builtins.toString version}
      Expected version: ${builtins.toString deps-lock-version}

      Re-generate the lock file with
      nix run github:jlesquembre/clj-nix#deps-lock
    ''
);
linkFarm "clj-cache" ([
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
  # {
  #   name = "cp-cache";
  #   path = cp-cache;
  # }
]
++ (lib.lists.optional (!builtins.isNull clojure-project)
  {
    name = "code";
    path = clojure-project;
  })
)
