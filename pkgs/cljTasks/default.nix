{ stdenv
, lib
, mkCljBin
, mkGraalBin
}:

let cljDrv =
  mkCljBin {
    projectSrc = ./.;
    name = "me.lafuente/tasks";
    version = "1.0";
    main-ns = "cljnix.tasks";
  };
in
mkGraalBin {
  inherit cljDrv;
}
