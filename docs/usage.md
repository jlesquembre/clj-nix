## Usage

This project requires [Nix Flakes](https://nixos.wiki/wiki/Flakes)

### New project template

```bash
nix flake new --template github:jlesquembre/clj-nix ./my-new-project
cd ./my-new-project
git init
git add .
```

Remember that with flakes, only the files tracked by git are recognized by Nix.

Templates are for new projects. If you want to add `clj-nix` to an existing
project, I suggest just copy the parts you need from the template (located here:
[clj-nix/templates/default](https://github.com/jlesquembre/clj-nix/tree/main/templates/default))
