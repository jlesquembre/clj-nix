# vi: ft=sh

load helpers

setup_file() {
  bats_require_minimum_version 1.5.0

  # For debugging
  # project_dir="/tmp/_cljs-nix_project"

  setup_temp_project_vars "cljs-nix_project"

  cljs_project_path="$cljnix_dir/test/clojurescript-example-project"
  copy_and_init_project "$cljs_project_path"
  echo "cljnixUrl: $cljnix_dir" | mustache "$project_dir/flake.template" > "$project_dir/flake.nix"

  cd "$project_dir" || exit
  nix flake lock
  git init
  git add .

  # Generate lockfile and add to git for build test
  nix run "$cljnix_dir#deps-lock"
  git add deps-lock.json
}

# bats test_tags=cljs
@test "Generate lockfile (ClojureScript)" {
    cd "$project_dir" || exit
    nix_run_and_log "$cljnix_dir#deps-lock"
    [ -f deps-lock.json ]
    grep -q "org.clojure/clojurescript" deps-lock.json
}

# bats test_tags=cljs
@test "Build ClojureScript app" {
    cd "$project_dir" || exit
    nix_build_with_result .#default
    [ -d result/js ]
}
