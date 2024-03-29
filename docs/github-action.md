### Github action

It's possible to add a GitHub action to automatically update the
`deps-lock.json` file on changes:

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
      - uses: actions/checkout@v3

      - uses: cachix/install-nix-action@v17

      - name: Update deps-lock
        run: "nix run github:jlesquembre/clj-nix#deps-lock"

      - name: Create Pull Request
        uses: peter-evans/create-pull-request@v4.0.3
        with:
          commit-message: Update deps-lock.json
          title: Update deps-lock.json
          branch: update-deps-lock
```
