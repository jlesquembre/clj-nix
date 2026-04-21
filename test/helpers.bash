#!/usr/bin/env bash
# Common helper functions for bats tests

# Use podman if available, otherwise docker
container_runtime() {
  if command -v podman &> /dev/null; then
    echo "podman"
  else
    echo "docker"
  fi
}

# Skip container tests on macOS unless remote builders are configured
skip_if_darwin_without_remote_builders() {
  if [[ "$OSTYPE" == "darwin"* ]] && ! nix show-config | grep -q "builders.*ssh"; then
    skip "Container tests require Linux remote builders on macOS (see: https://nixos.org/manual/nix/stable/advanced-topics/distributed-builds.html)"
  fi
}

# Build a nix package and log the derivation path
# Usage: nix_build_and_log <flake-ref>
nix_build_and_log() {
  local flake_ref="$1"
  nix build "$flake_ref" --no-link --print-out-paths >> "$DERIVATIONS"
}

# Build a nix package, log the derivation path, and create a result symlink
# Usage: nix_build_with_result <flake-ref>
nix_build_with_result() {
  local flake_ref="$1"
  nix build "$flake_ref" --print-out-paths >> "$DERIVATIONS"
}

# Run a nix package and log the derivation path
# Usage: nix_run_and_log <flake-ref> [args...]
nix_run_and_log() {
  local flake_ref="$1"
  shift
  nix build "$flake_ref" --no-link --print-out-paths >> "$DERIVATIONS"
  nix run "$flake_ref" -- "$@"
}

# Setup a temporary project directory for testing
# Usage: setup_temp_project <base-name>
# Sets: project_dir, cljnix_dir
setup_temp_project_vars() {
  local base_name="${1:-clj-nix_project}"
  project_dir="$BATS_FILE_TMPDIR/$base_name"
  export project_dir
  cljnix_dir=$(dirname "$BATS_TEST_DIRNAME")
  export cljnix_dir
}

# Copy a directory and initialize as a test project
# Usage: copy_and_init_project <source-dir>
copy_and_init_project() {
  local source_dir="$1"
  cp -r "$source_dir/." "$project_dir"
}

# Create a new project from template and configure flake
# Usage: create_project_from_template
# Note: Expects cljnix_dir_copy to be set by caller
create_project_from_template() {
  nix flake new --template . "$project_dir"
  echo "cljnixUrl: $cljnix_dir_copy" | mustache "$cljnix_dir/test/integration/flake.template" > "$project_dir/flake.nix"
}

# Initialize git and lock the flake
# Usage: init_git_and_lock
init_git_and_lock() {
  cd "$project_dir" || exit 1
  nix flake lock
  git init
  git add .
}

# Assert that output matches expected value
# Usage: assert_output_equals <expected>
assert_output_equals() {
  local expected="$1"
  [ "$output" = "$expected" ]
}

# Backup a file with .bkp extension
# Usage: backup_file <file>
backup_file() {
  local file="$1"
  cp "$file" "${file}.bkp"
}

# Compare file with its backup
# Usage: compare_with_backup <file>
compare_with_backup() {
  local file="$1"
  cmp "$file" "${file}.bkp"
}

# Restore file from backup and remove backup
# Usage: restore_from_backup <file>
restore_from_backup() {
  local file="$1"
  cp "${file}.bkp" "$file"
}
