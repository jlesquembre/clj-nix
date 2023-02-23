# vi: ft=sh

@test "nix build .#clj-builder" {
    nix build .#clj-builder --no-link
}

@test "nix build .#deps-lock" {
    nix build .#deps-lock --no-link
}
