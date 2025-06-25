# vi: ft=sh

setup_file() {
  bats_require_minimum_version 1.5.0

  # For debugging
  # project_dir="/tmp/_clj-nix_project"

  project_dir="$BATS_FILE_TMPDIR/clj-nix_project"
  export project_dir
  cljnix_dir=$(dirname "$BATS_TEST_DIRNAME")
  export cljnix_dir

  leiningen_project_path="$cljnix_dir/test/leiningen-example-project"
  cp -r "$leiningen_project_path/." "$project_dir"
  echo "cljnixUrl: $cljnix_dir" | mustache "$project_dir/flake.template" > "$project_dir/flake.nix"

  cd "$project_dir" || exit
  ls -la
  cat flake.nix
  nix flake lock
}

@test "Generate lockfile" {
    cp deps-lock.json deps-lock.json.bkp
    nix build "$cljnix_dir#deps-lock" --no-link --print-out-paths >> "$DERIVATIONS"
    nix run "$cljnix_dir#deps-lock" -- --lein
    cmp deps-lock.json deps-lock.json.bkp
}

@test "Generate lockfile for specific Leiningen profile only" {
    cp deps-lock.json deps-lock.json.bkp
    nix build "$cljnix_dir#deps-lock" --no-link --print-out-paths >> "$DERIVATIONS"
    nix run "$cljnix_dir#deps-lock" -- --lein --lein-profiles foobar
    cmp deps-lock.json deps-lock-foobar-profile.json
    cp deps-lock.json.bkp deps-lock.json
}

@test "mkCljBin" {
    nix build .#mkCljBin-test --print-out-paths >> "$DERIVATIONS"
    run -0 ./result/bin/cljdemo
    [ "$output" = "Hello, World!" ]
}

@test "mkCljBin with tests" {
    nix build .#mkCljBin-test-with-tests --print-out-paths >> "$DERIVATIONS"
    run -0 ./result/bin/cljdemo
    [ "$output" = "Hello, World!" ]
}
