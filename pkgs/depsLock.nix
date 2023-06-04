{ jq, writeShellScriptBin, clj-builder }:
writeShellScriptBin "deps-lock"
  ''
    set -euo pipefail
    ${clj-builder} "$@" | ${jq}/bin/jq . > deps-lock.json
  ''
