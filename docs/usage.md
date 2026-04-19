# Usage

This project requires [Nix Flakes](https://nixos.wiki/wiki/Flakes)

## New project template

```bash
nix flake new --template github:jlesquembre/clj-nix ./my-new-project
cd ./my-new-project
git init
git add .

# Build and test the binary
nix build .
./result/bin/cljdemo
```

Remember that with flakes, only the files tracked by git are recognized by Nix.

Templates are for new projects. If you want to add `clj-nix` to an existing
project, copy the parts you need from the template (located at:
[clj-nix/templates/default](https://github.com/jlesquembre/clj-nix/tree/main/templates/default)).

## Next Steps

After creating your project:

1. Generate a lock file: See [Lock File documentation](./lock-file.md)
2. Customize your build: See [API documentation](./api.md) or [Nix Module documentation](./nix-module.md)
3. Follow the [Tutorial](./tutorial.md) for advanced configurations
