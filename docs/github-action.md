# GitHub Action

You can add a GitHub Action to automatically update the `deps-lock.json` file
when dependencies change:

```yaml
name: "Update deps-lock.json"
on:
  push:
    paths:
      - "**/deps.edn"

jobs:
  update-lock:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: cachix/install-nix-action@v30

      - name: Update deps-lock
        run: "nix run github:jlesquembre/clj-nix#deps-lock"

      - name: Create Pull Request
        uses: peter-evans/create-pull-request@v7
        with:
          commit-message: Update deps-lock.json
          title: Update deps-lock.json
          branch: update-deps-lock
```

!!! note

    The action versions above are current as of 2025. You may want to check
    for newer versions of these actions:

    - [actions/checkout](https://github.com/actions/checkout)
    - [cachix/install-nix-action](https://github.com/cachix/install-nix-action)
    - [peter-evans/create-pull-request](https://github.com/peter-evans/create-pull-request)
