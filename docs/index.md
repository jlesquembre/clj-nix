# clj-nix

Nix helpers for Clojure projects

STATUS: alpha.

## Introduction

The main goal of the project is to reduce the friction between Clojure and Nix.
Nix is a great tool to build and deploy software, but Clojure is not well
supported in the Nix ecosystem.

`clj-nix` tries to improve the situation, providing Nix helpers to interact with
Clojure projects

The main difficulty of packaging a Clojure application with Nix is that the
derivation is restricted from performing any network request. But Clojure does
network requests to resolve the dependency tree. Some network requests are done
by Maven, since Clojure uses Maven under the hood. On the other hand, since
[git deps](https://clojure.org/news/2018/01/05/git-deps) were introduced,
Clojure also access the network to resolve the git dependencies.

A common solution to this problem are **lock files**. A lock file is a snapshot
of the entire dependency tree, usually generated the first time we install the
dependencies. Subsequent installations will use the lock file to install exactly
the same version of every dependency. Knowing beforehand all the dependencies,
we can download and cache all of them, avoiding network requests during the
build phase with Nix.

Ideally, we could reuse a lock file generated by Maven or Clojure itself, but
lock files are not popular in the JVM/Maven ecosystem. For that reason,
`clj-nix` provides a way to create a lock file from a `deps.edn` file. Creating
a lock file is a prerequisite to use the Nix helpers provided by `clj-nix`

**GOALS**:

- Create a binary from a clojure application
- Create an optimized JDK runtime to execute the clojure binary
- Create GraalVM native images from a clojure application
- Simplify container creation for a Clojure application
- Run any arbitrary clojure command at Nix build time (like `clj -T:build` or
  `clj -M:test`)

## Similar projects

- [dwn](https://github.com/webnf/dwn)
- [clj2nix](https://github.com/hlolli/clj2nix)
- [mvn2nix](https://github.com/fzakaria/mvn2nix)
- [clojure-nix-locker](https://github.com/bevuta/clojure-nix-locker)