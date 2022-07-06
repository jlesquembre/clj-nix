{ stdenv
, lib
, mkCljBin
, mkGraalBin
, runCommand
, babashka
}:

let
  cljDrv =
    mkCljBin {
      projectSrc = ./.;
      name = "me.lafuente/bb-task-parser";
      version = "1.0";
      main-ns = "cljnix.tasks";
    };
  bbTaskParser = mkGraalBin { inherit cljDrv; };
in

taskConfig:

let
  bbFile = if (builtins.isAttrs taskConfig) then taskConfig.file else taskConfig;
  bb = if (builtins.isAttrs taskConfig) then taskConfig.bb else babashka;
  json-config =
    runCommand "genCommands"
      { buildInputs = [ bbTaskParser ]; }
      ''
        bb-task-parser "${bbFile}" > $out
      '';
  config = builtins.fromJSON (builtins.readFile "${json-config}");
in
builtins.map
  ({ docstring ? null, fn-name }:
  let
    fn = if builtins.isNull (config.ns) then fn-name else "${config.ns}/${fn-name}";
  in
  {
    name = fn-name;
    help = docstring;
    command =
      ''
        ${bb}/bin/bb --init ${bbFile} -e '(apply ${fn} *command-line-args*)' $@
      '';
  })
  config.tasks
