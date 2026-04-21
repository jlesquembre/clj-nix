# vi: ft=sh

load helpers

@test "nix build .#clj-builder" {
    nix_build_and_log .#clj-builder
}

@test "nix build .#deps-lock" {
    nix_build_and_log .#deps-lock
}
