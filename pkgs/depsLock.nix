{ jq, writeShellScriptBin, nix-prefetch-git, clj-builder }:
writeShellScriptBin "deps-lock"
  ''
    ${clj-builder} | ${jq}/bin/jq . > deps-lock.json
  ''
