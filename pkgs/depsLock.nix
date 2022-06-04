{ jq, writeShellScriptBin, nix-prefetch-git, clj-builder }:
writeShellScriptBin "deps-lock"
  ''
    set -o pipefail
    ${clj-builder} | ${jq}/bin/jq . > deps-lock.json
  ''
