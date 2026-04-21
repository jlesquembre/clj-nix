/* mkCljsApp - Build a ClojureScript application

This builder creates JavaScript applications from ClojureScript projects using shadow-cljs.

OUTPUTS:
- Browser builds: Static files (HTML, JS, CSS) ready for deployment
- Node.js builds: Executable JavaScript for Node.js runtime

EXTENSIBILITY:
The build system supports custom build processes via the `buildCommand` parameter.
Default uses `clj-builder cljs-compile` with shadow-cljs.
*/

{ stdenv
, lib
, clojure
, nodejs
, writeText

  # Custom utils
, clj-builder
, mk-deps-cache
}:

{
  # User options
  projectSrc
, name
, version ? "DEV"
, buildTarget ? "browser"  # "browser" or "node"
, buildId ? "app"
, buildCommand ? null  # Override default build with custom build script
, lockfile ? null
, shadow-cljs-opts ? null
, nodejs-package ? nodejs
, ...
}@attrs:

let

  extra-attrs = builtins.removeAttrs attrs [
    "projectSrc"
    "name"
    "version"
    "buildTarget"
    "buildId"
    "buildCommand"
    "lockfile"
    "shadow-cljs-opts"
    "nodejs-package"
    "nativeBuildInputs"
  ];

  deps-cache = mk-deps-cache {
    lockfile = if isNull lockfile then (projectSrc + "/deps-lock.json") else lockfile;
  };

  fullId = if (lib.strings.hasInfix "/" name) then name else "${name}/${name}";
  artifactId = builtins.elemAt (lib.strings.splitString "/" fullId) 1;

in
stdenv.mkDerivation ({
  inherit version;

  pname = lib.strings.sanitizeDerivationName artifactId;
  src = projectSrc;

  # Build time dependencies
  nativeBuildInputs =
    attrs.nativeBuildInputs or [ ]
      ++
      [
        clojure
        nodejs-package
      ];

  passthru = {
    inherit deps-cache fullId artifactId buildTarget buildId;
  };

  patchPhase =
    ''
      runHook prePatch
      ${clj-builder}/bin/clj-builder patch-git-sha "$(pwd)"
      runHook postPatch
    '';

  buildPhase =
    ''
      runHook preBuild

      export HOME="${deps-cache}"
      export JAVA_TOOL_OPTIONS="-Duser.home=${deps-cache}"

      # Make Node.js available for shadow-cljs (used for JavaScript processing)
      export PATH="${nodejs-package}/bin:$PATH"
    ''
    +
    (
      if builtins.isNull buildCommand then
        ''
          # Default ClojureScript build using clj-builder
          ${clj-builder}/bin/clj-builder cljs-compile "${fullId}" "${version}" "${buildId}" "${buildTarget}"
        ''
      else
        ''
          # Custom build command
          ${buildCommand}
        ''
    )
    +
    ''
      runHook postBuild
    '';

  installPhase =
    ''
      runHook preInstall

      mkdir -p $out

      # Find and copy compiled output
      if [ -d "target/cljs/${buildId}" ]; then
        cp -r target/cljs/${buildId}/* $out/
      elif [ -d "public" ]; then
        # shadow-cljs default output for browser builds
        cp -r public/* $out/
      else
        echo "Warning: No compiled output found in expected locations"
        # Copy any .js files found in target
        find target -name "*.js" -exec cp {} $out/ \;
      fi

      # For Node.js builds, create executable wrapper
      ${if buildTarget == "node" then ''
        if [ -f "$out/main.js" ]; then
          cat > $out/bin/${artifactId} <<EOF
      #!${nodejs-package}/bin/node
      require('$out/main.js');
      EOF
          chmod +x $out/bin/${artifactId}
        fi
      '' else ""}

      runHook postInstall
    '';

  meta = {
    description = "ClojureScript application: ${name}";
    platforms = lib.platforms.all;
  };
} // extra-attrs)
