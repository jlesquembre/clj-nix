# Changelog

## Unreleased

## 0.4.0 (2024-10-06)

- Added support to git dependencies in private repositories. See #128, thanks
  @bendlas

- Improved gitlibs support. See #120, thanks @mjmeintjes

- Added new options to configure the default builder (`builder-..` options). See
  #106 and
  [mkCljBin API docs](https://jlesquembre.github.io/clj-nix/api/#mkcljbin)

- Added support for additional maven repositories. Fixes #98.

- Refactor CLI, now we use [babashka.cli](https://github.com/babashka/cli) to
  parse the command line arguments (see #54 and #63, thanks to @bendlas to help
  with this one)

- Added new flags to `deps-lock` command: `--deps-include`, `--deps-exclude`,
  `--alias-include` and `--alias-exclude`

- `deps-lock` command now supports babashka `bb.edn` files, with the `--bb` flag

  - To be able to run babashka in a Nix build, I upstreamed some changes to the
    [babashka derivation on nixpkgs](https://github.com/NixOS/nixpkgs/pull/241119)

- `deps-lock` command checks if `deps-lock.json` is tracked by git. If not, runs
  `git add --intent-to-add`

- Add `extraJdkModules` option to `customJdk`

- Add `wrap` option to `mkBabashka`

- Check that the `main-ns` has a `:gen-class` in `mkCljBin` (see #39, thanks
  @slimslenderslacks)

## 0.3.0 (2022-08-03)

- Fix `mkCljCli` helper function
- Add support for Leiningen projects
- Add `lockfile` option to `mkCljBin`
- Add `mkBabashka`
- Add `bbTasksFromFile`
- Add `multiRelease` option to `customJdk`
- Add option to preload deps to the nix store

## 0.2.0 (2022-06-13)

- Add overlays(#19). Thanks to @kenranunderscore and @Sohalt
- Accept sha for annotated tags in deps.edn. For details see
  https://clojurians.slack.com/archives/C6QH853H8/p1636404490163500
- Better support for maven snapshots.

## 0.1.0 (2022-06-06)

- Added support for `:local/root` dependecies
- Added support for deps.edn aliases
- New deps-lock.json format
- Reduce network requests to generate the lock file, making generation faster
- Now the classpath is computed at build time
- Updated arguments for `mkCljBin`, `mkGraalBin` and `customJdk`, check
  documentation for details
- Added `mkCljLib` nix function

## 0.0.0 (2022-04-04)

- Initial release
