### Generate lock file

As mentioned, a lock file must be generated in advance:

```bash
nix run github:jlesquembre/clj-nix#deps-lock
```

That command looks for `deps.edn` files in your project and generates a
`deps-lock.json` file in the current directory. Remember to re-run it if you
update your dependencies.

By default all dependencies in all `deps.edn` files are included. It is possible
to exclude certain `deps.edn` files and/or aliases. To see the full list of
options of `deps-lock` run:

```bash
nix run github:jlesquembre/clj-nix#deps-lock -- --help
```

It is possible to add the dependencies to the nix store during the lock file
generation. Internally we are invoking the `nix store add-path` command. By
default, it's disabled because that command is relatively slow. To add the
dependencies to the nix store, set the environment variable
`CLJNIX_ADD_NIX_STORE` to true, e.g.:

```bash
CLJNIX_ADD_NIX_STORE=true nix run github:jlesquembre/clj-nix#deps-lock
```

#### Ignore deps.edn files

Sometimes it could be useful to ignore some `deps.edn` files, to do that, just
pass the list of files to ignore the the `deps-lock` command:

```bash
nix run github:jlesquembre/clj-nix#deps-lock -- --deps-exclude ignore/deps.edn
```

There is also a `--deps-include` option, to include only certain files.

#### Ignore aliases

To exclude the dependencies defined in some aliases use the `--alias-exclude`
option:

```bash
nix run github:jlesquembre/clj-nix#deps-lock -- --alias-exclude test
```

There is also a `--alias-include` option, to include only certain aliases.

#### Git dependencies in private/ssh repositories

In order to use the nix builtin fetcher on a git dependency, add a key-value
`:clj-nix.git/fetch :builtins.fetchTree` to the dependency in `deps.edn`, e.g.

```edn
{:deps {private/dependency {:git/url "git@private.host:secret/repo.git"
                            :git/sha "0000000000000000000000000000000000000000"
                            :clj-nix.git/fetch :builtins.fetchTree}}}
```

This should work well in many cases where a repository can be accessed
with the help of ssh-agent or other credential mechanisms, that nix
builtin fetch supports.

The trade-off (and reason that's not the default) is, that the
dependency will be fetched at evaluation time, causing downloads even
during `--dry-run`; Pending resolution of an [issue in nix](https://github.com/NixOS/nix/issues/9077).

#### Babashka dependencies

Dependencies on `bb.edn` files can be added to the `deps-lock.json` file:

```bash
nix run github:jlesquembre/clj-nix#deps-lock -- --bb
```

#### Leiningen

Leiningen projects are supported. Use the `--lein` option to add the
`project.clj` dependencies to the lock file. This option can be combined with
ignored files:

```bash
nix run github:jlesquembre/clj-nix#deps-lock -- --lein
```

Keep in mind that `deps-lock` command is not optimized for Leiningen projects,
it will download all the maven dependencies every time we generate the lock
file. For that reason, it is recommended to add a `deps.edn` file with the same
dependencies to Leiningen projects. That way, we reduce the number of network
requests when the `deps-lock` command is invoked.

There are projects to automatically generate a `deps.edn` file from a Leiningen
project (e.g.: [depify](https://github.com/hagmonk/depify))

!!! warning

    Leiningen projects **must** define a `buildCommand` in the `mkCljBin`
    function. The default build command assumes a `deps.edn` project.
