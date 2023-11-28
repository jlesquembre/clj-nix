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
