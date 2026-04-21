# vi: ft=sh

load helpers

setup_file() {
  bats_require_minimum_version 1.5.0

  # For debugging
  # project_dir="/tmp/_clj-nix_project"

  setup_temp_project_vars "clj-nix_project"

  leiningen_project_path="$cljnix_dir/test/leiningen-example-project"
  copy_and_init_project "$leiningen_project_path"
  echo "cljnixUrl: $cljnix_dir" | mustache "$project_dir/flake.template" > "$project_dir/flake.nix"

  cd "$project_dir" || exit
  ls -la
  cat flake.nix
  nix flake lock
}

# bats test_tags=lein
@test "Generate lockfile (leiningen)" {
    backup_file deps-lock.json
    nix_run_and_log "$cljnix_dir#deps-lock" --lein
    compare_with_backup deps-lock.json
}

# bats test_tags=lein
@test "Generate lockfile for specific Leiningen profile only (leiningen)" {
    backup_file deps-lock.json
    nix_run_and_log "$cljnix_dir#deps-lock" --lein --lein-profiles foobar
    cmp deps-lock.json deps-lock-foobar-profile.json
    restore_from_backup deps-lock.json
}

# bats test_tags=lein
@test "mkCljBin (leiningen)" {
    skip
    nix_build_with_result .#mkCljBin-test
    run -0 ./result/bin/cljdemo
    assert_output_equals "Hello, World!"
}

# bats test_tags=lein
@test "mkCljBin with tests (leiningen)" {
    skip
    nix_build_with_result .#mkCljBin-test-with-tests
    run -0 ./result/bin/cljdemo
    assert_output_equals "Hello, World!"
}
