{
  pkgs,
  ...
}:
let
  script = pkgs.writeText "fake-git.clj"
    (builtins.readFile ./fake_git.clj);
in
pkgs.writeScriptBin "git"
  ''
  ${pkgs.babashka}/bin/bb -cp "" ${script} "$@"
  ''
