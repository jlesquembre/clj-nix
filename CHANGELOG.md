# Changelog

## Unreleased

- Fix mkCljCli helper function
- Add support for Leiningen projects
- Add lockfile option to mkCljBin
- Add mkBabashka

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
