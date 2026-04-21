# vi: ft=sh

load helpers

setup_file() {

  bats_require_minimum_version 1.5.0

  # For debugging
  # project_dir="/tmp/_clj-nix_project"

  setup_temp_project_vars "clj-nix_project"

  cljnix_dir_copy="/tmp/_clj-nix_copy"
  export cljnix_dir_copy
  cp -r "$cljnix_dir" "$cljnix_dir_copy"

  create_project_from_template
  init_git_and_lock
}

teardown_file() {
    $(container_runtime) rmi jvm-container-test
    $(container_runtime) rmi graal-container-test
    rm -rf "$cljnix_dir_copy"
}

@test "Generate deps-lock.json" {
    backup_file deps-lock.json
    nix_run_and_log "$cljnix_dir#deps-lock"
    compare_with_backup deps-lock.json
}

@test "New lock files are added to git" {
    git rm --cached deps-lock.json
    nix run "$cljnix_dir#deps-lock"
    git ls-files --error-unmatch deps-lock.json
}


@test "nix build .#mkCljBin-test" {
    nix_build_with_result .#mkCljBin-test
    run -0 ./result/bin/cljdemo
    assert_output_equals "Hello from CLOJURE!!!"
}

@test "nix build .#customJdk-test" {
    nix_build_with_result .#customJdk-test
    run -0 ./result/bin/cljdemo
    assert_output_equals "Hello from CLOJURE!!!"
}

# bats test_tags=graal
@test "nix build .#mkGraalBin-test" {
    nix_build_with_result .#mkGraalBin-test
    run -0 ./result/bin/cljdemo
    assert_output_equals "Hello from CLOJURE!!!"
}

# bats test_tags=docker
@test "nix build .#jvm-container-test" {
    skip_if_darwin_without_remote_builders
    nix_build_with_result .#jvm-container-test
    $(container_runtime) load -i result
    run -0 "$(container_runtime)" run --rm jvm-container-test:latest
    assert_output_equals "Hello from CLOJURE!!!"
}

# bats test_tags=docker,graal
@test "nix build .#graal-container-test" {
    skip_if_darwin_without_remote_builders
    nix_build_with_result .#graal-container-test
    $(container_runtime) load -i result
    run -0 "$(container_runtime)" run --rm graal-container-test:latest
    assert_output_equals "Hello from CLOJURE!!!"
}

# bats test_tags=babashka
@test "nix build .#babashka-test" {
    nix_build_with_result .#babashka-test
    run -0 ./result/bin/bb -e "(inc 101)"
    assert_output_equals "102"
    run ! ./result/bin/bb -e "(require '[next.jdbc])"
}

# bats test_tags=babashka
@test "nix build .#babashka-with-features-test" {
    nix_build_with_result .#babashka-with-features-test
    ./result/bin/bb -e "(require '[next.jdbc])"
}
