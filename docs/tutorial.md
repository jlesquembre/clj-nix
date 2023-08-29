## Tutorial

Source code for this tutorial can be found here:
https://github.com/jlesquembre/clj-demo-project

### Init

There is a template to help you start your new project:

```bash
nix flake new --template github:jlesquembre/clj-nix ./my-new-project
```

For this tutorial you can clone the final version:

```bash
git clone git@github.com:jlesquembre/clj-demo-project.git
```

First thing we need to do is to generate a lock file:

```bash
nix run github:jlesquembre/clj-nix#deps-lock
git add deps-lock.json
```

!!! note

    The following examples assume that you cloned the demo repository, and
    you are executing the commands from the root of the repository. But with Nix
    flakes, it's possible to point to the remote git repository. E.g.: We can
    replace `nix run .#foo` with `nix run github:/jlesquembre/clj-demo-project#foo`

### Create a binary from a Clojure application

First, we create a new package in our flake:

```nix
clj-tuto = cljpkgs.mkCljBin {
  projectSrc = ./.;
  name = "me.lafuente/cljdemo";
  main-ns = "demo.core";
};
```

Let's try it:

```bash
nix build .#clj-tuto
./result/bin/clj-tuto
# Or
nix run .#clj-tuto
```

Nice! We have a binary for our application. But how big is our app? We can find
it with:

```bash
nix path-info -sSh .#clj-tuto
# Or to see all the dependencies:
nix path-info -rsSh .#clj-tuto
```

Um, the size of our application is `1.3G`, not ideal if we want to create a
container. We can use a headless JDK to reduce the size, let's try that:

```nix
clj-tuto = cljpkgs.mkCljBin {
  projectSrc = ./.;
  name = "me.lafuente/cljdemo";
  main-ns = "demo.core";
  jdkRunner = pkgs.jdk17_headless;
};
```

```bash
nix build .#clj-tuto
nix path-info -sSh .#clj-tuto
```

Good, now the size is `703.9M`. It's an improvement, but still big. To reduce
the size, we can use the `customJdk` helper.

### Create custom JDK for a Clojure application

We add a package to our flake, to build a customized JDK for our Clojure
application:

```nix
jdk-tuto = cljpkgs.customJdk {
  cljDrv = self.packages."${system}".clj-tuto;
  locales = "en,es";
};
```

```bash
nix build .#jdk-tuto
nix path-info -sSh .#jdk-tuto
```

Not bad! We reduced the size to `96.3M`. That's something we can put in a
container. Let's create a container with our application.

### Create a container

Again, we add a new package to our flake, in this case it will create a
container:

```nix
clj-container =
  pkgs.dockerTools.buildLayeredImage {
    name = "clj-nix";
    tag = "latest";
    config = {
      Cmd = clj-nix.lib.mkCljCli self.packages."${system}".jdk-tuto { };
    };
  };
```

```bash
nix build .#clj-container
nix path-info -sSh .#clj-container
```

The container's size is `52.8M`. Wait, how can be smaller than our custom JDK
derivation? There are 2 things to consider.

First, notice that we used the `mkCljCli` helper function. In the original
version, our binary is a bash script, so `bash` is a dependency. But in a
container we don't need `bash`, the container runtime can launch the command,
and we can reduce the size by removing `bash`

Second, notice that the image was compressed with gzip.

Let's load and execute the image:

```bash
docker load < result
docker run -it --rm clj-nix
docker images
```

Docker reports an image size of `99.2MB`

### Create a native image with GraalVM

If we want to continue reducing the size of our derivation, we can compile the
application with GraalVM. Keep in mind that size it's not the only factor to
consider. There is a nice slide from the GraalVM team, illustrating what
technology to use for which use case:

![GraalVM performance](./graal-performance.jpeg)

(The image was taken from a
[tweet by Thomas Würthinger](https://twitter.com/thomaswue/status/1145603781108928513))

For more details, see:
[Does GraalVM native image increase overall application performance or just reduce startup times?](https://stackoverflow.com/a/59488814/799785)

Let's compile our Clojure application with GraalVM:

```nix
graal-tuto = cljpkgs.mkGraalBin {
  cljDrv = self.packages."${system}".clj-tuto;
};
```

```bash
nix build .#graal-tuto
./result/bin/clj-tuto
nix path-info -sSh .#graal-tuto
```

The size is just `43.4M`.

We can create a container from this derivation too:

```nix
graal-container =
  let
    graalDrv = self.packages."${system}".graal-tuto;
  in
  pkgs.dockerTools.buildLayeredImage {
    name = "clj-graal-nix";
    tag = "latest";
    config = {
      Cmd = "${graalDrv}/bin/${graalDrv.name}";
    };
  };
```

```bash
docker load < result
docker run -it --rm clj-graal-nix
```

In this case, the container image size is `45.3MB`, aproximately half the size
of the custom JDK image.
