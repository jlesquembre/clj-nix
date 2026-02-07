# vi: ft=sh

# Use podman if available, otherwise docker
container_runtime() {
  if command -v podman &> /dev/null; then
    echo "podman"
  else
    echo "docker"
  fi
}

setup_file() {

  bats_require_minimum_version 1.5.0

  # For debugging
  # project_dir="/tmp/_clj-nix_project"

  project_dir="$BATS_FILE_TMPDIR/clj-nix_project"
  export project_dir
  cljnix_dir=$(dirname "$BATS_TEST_DIRNAME")
  export cljnix_dir

  cljnix_dir_copy="/tmp/_clj-nix_copy"
  export cljnix_dir_copy
  cp -r "$cljnix_dir" "$cljnix_dir_copy"

  nix flake new --template . "$project_dir"
  echo "cljnixUrl: $cljnix_dir_copy" | mustache "$cljnix_dir/test/integration/flake.template" > "$project_dir/flake.nix"

  cd "$project_dir" || exit
  nix flake lock
  git init
  git add .
}

teardown_file() {
    $(container_runtime) rmi jvm-container-test
    $(container_runtime) rmi graal-container-test
    rm -rf "$cljnix_dir_copy"
}

@test "Generate deps-lock.json" {
    cp deps-lock.json deps-lock.json.bkp
    nix build "$cljnix_dir#deps-lock" --no-link --print-out-paths >> "$DERIVATIONS"
    nix run "$cljnix_dir#deps-lock"
    cmp deps-lock.json deps-lock.json.bkp
}

@test "New lock files are added to git" {
    git rm --cached deps-lock.json
    nix run "$cljnix_dir#deps-lock"
    git ls-files --error-unmatch deps-lock.json
}


@test "nix build .#mkCljBin-test" {
    nix build .#mkCljBin-test --print-out-paths >> "$DERIVATIONS"
    run -0 ./result/bin/cljdemo
    [ "$output" = "Hello from CLOJURE!!!" ]
}

@test "nix build .#customJdk-test" {
    nix build .#customJdk-test --print-out-paths >> "$DERIVATIONS"
    run -0 ./result/bin/cljdemo
    [ "$output" = "Hello from CLOJURE!!!" ]
}

# bats test_tags=graal
@test "nix build .#mkGraalBin-test" {
    nix build .#mkGraalBin-test --print-out-paths >> "$DERIVATIONS"
    run -0 ./result/bin/cljdemo
    [ "$output" = "Hello from CLOJURE!!!" ]
}

# bats test_tags=docker
@test "nix build .#jvm-container-test" {
    nix build .#jvm-container-test --print-out-paths >> "$DERIVATIONS"
    $(container_runtime) load -i result
    run -0 "$(container_runtime)" run --rm jvm-container-test:latest
    [ "$output" = "Hello from CLOJURE!!!" ]
}

# bats test_tags=docker,graal
@test "nix build .#graal-container-test" {
    nix build .#graal-container-test --print-out-paths >> "$DERIVATIONS"
    $(container_runtime) load -i result
    run -0 "$(container_runtime)" run --rm graal-container-test:latest
    [ "$output" = "Hello from CLOJURE!!!" ]
}

# bats test_tags=babashka
@test "nix build .#babashka-test" {
    nix build .#babashka-test --print-out-paths >> "$DERIVATIONS"
    run -0 ./result/bin/bb -e "(inc 101)"
    [ "$output" = "102" ]
    run ! ./result/bin/bb -e "(require '[next.jdbc])"
}

# bats test_tags=babashka
@test "nix build .#babashka-with-features-test" {
    nix build .#babashka-with-features-test --print-out-paths >> "$DERIVATIONS"
    ./result/bin/bb -e "(require '[next.jdbc])"
}
