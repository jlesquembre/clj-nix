## FAQ

### **How can I define extra runtime dependencies?**

If you try to call a external program from your Clojure application, you will
get an error (something like
`Cannot run program "some-program": error=2, No such file or directory`). One
possible solution is to wrap the binary:

```nix
cljpkgs.mkCljBin {
# ...
nativeBuildInputs = [ pkgs.makeWrapper ];
postInstall = ''
  wrapProgram $binaryPath \
    --set PATH ${pkgs.lib.makeBinPath [ pkgs.cowsay ]}
'';
}
```

Notice that the `$binaryPath` is a proper Bash variable. It is created by
`mkCljBin` during the install phase.

or if you want to define the dependencies in a docker image:

```nix
pkgs.dockerTools.buildLayeredImage {
  # ...
  config = {
    Env = [ "PATH=${pkgs.lib.makeBinPath [ pkgs.cowsay ]}" ];
  };
};
```

### **My customJdk application has an SSL handshake_failure**

If after creating an application using the `customJdk` package, you might see an error like the following:

```
javax.net.ssl.SSLHandshakeException: Received fatal alert: handshake_failure
```

This could be caused by a missing jdkModule, such as `jdk.crypto.ec`, which is required for many `https` connections.
Add the missing modules explicitly.

```nix
custom-jdk = pkgs.clj-nix.customJdk {
  cljDrv = clj;
  jdkBase = pkgs.jdk17_headless;
  # locales = "en";
  javaOpts = [];
  extraJdkModules = ["java.security.jgss" "java.security.sasl" "jdk.crypto.ec"];
};
```
