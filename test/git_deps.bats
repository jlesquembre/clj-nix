# vi: ft=sh

setup_file() {
  bats_require_minimum_version 1.5.0

  project_dir="$BATS_FILE_TMPDIR/clj-nix_project"
  export project_dir
  cljnix_dir=$(dirname "$BATS_TEST_DIRNAME")
  export cljnix_dir

  cp -r "$cljnix_dir/test/git-deps-example-project/." "$project_dir"
  echo "cljnixUrl: $cljnix_dir" | mustache "$project_dir/flake.template" > "$project_dir/flake.nix"

  cd "$project_dir" || exit
  nix flake lock
}

# Transitive git deps by :git/tag, and two revisions of one repo, make
# tools.deps run git while it resolves the basis.
# bats test_tags=mkCljLib
@test "mkCljLib with transitive git deps" {
    nix build .#mkCljLib-test --print-out-paths >> "$DERIVATIONS"
    run -0 jar tf result/gitdeps-1.0.0.jar
    [[ "$output" == *"META-INF/maven/me.lafuente/gitdeps/pom.xml"* ]]
    [[ "$output" == *"gitdeps/core.clj"* ]]
}
