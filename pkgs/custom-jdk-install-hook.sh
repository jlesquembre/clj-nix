# shellcheck shell=bash

# USAGE:
#
# In mkDerivation:
#  - outputs = [ "out" "jdk" ]: if $jdk is defined, the custom JDK is compiled
#    here, if not, $out is used
#  - nativeBuildInputs = [ someJdk customJdkInstallHook ]: The hook doesn't
#    provide a default jdk
#
#  Env variables:
#  - jarPath: Path to a uberjar (MANDATORY)
#  - jdkModules: Option passed to jlink --add-modules. If null, jeps will be
#                used to analyze the cljDrv and pick the necessary modules
#                automatically. (OPTIONAL)
# - extraJdkModules: Extra JDK modules appended to jdkModules (OPTIONAL)
# - locales: Option passed to jlink --include-locales. (OPTIONAL)

customJdkInstallHook() {
  echo "Executing customJdkInstallHook"

  shopt -s extglob
  runHook preInstall

  # Debug info
  java -version

  ## -z ${VAR-} -> VAR is EMPTY
  ## -n ${VAR-} -> VAR NOT empty
  if [ -z "${jdkModules-}" ]; then

    if [ -z "${jarPath-}" ]; then
      export jdkModules="java.base"
    else
      if unzip -p "$jarPath" META-INF/MANIFEST.MF | grep "Multi-Release: true"; then
        export multiReleaseArgs="--multi-release base"
      fi
      # shellcheck disable=SC2086
      jdkModules=$(jdeps $multiReleaseArgs --ignore-missing-deps --print-module-deps "$jarPath")
      export jdkModules
    fi
  fi

  if [[ -n "${locales-}" && "$jdkModules" != *"jdk.localedata"* ]]; then
    localesFlag="--include-locales"

    # If 'locales' is defined, make sure to include "jdk.localedata"
    if [[ "$jdkModules" != *"jdk.localedata"* ]]; then
      jdkModules="${jdkModules},jdk.localedata"
    fi
  fi

  # Replace spaces with commas, jlink expects Comma-separated values
  extraJdkModules="${extraJdkModules//+( )/,}"
  if [ -n "${extraJdkModules-}" ]; then
    extraJdkModules=",$extraJdkModules"
  fi

  # Debug info
  echo "JDK modules: $jdkModules${extraJdkModules-}"
  echo "Locales: $localesFlag $locales"

  # shellcheck disable=SC2086
  jlink \
    --no-header-files \
    --no-man-pages \
    --add-modules "$jdkModules${extraJdkModules-}" \
    $localesFlag $locales \
    --compress 2 \
    --output "${jdk-$out}"

  # JDK generated from a JAR file, create a wrapper script
  if [ -n "${jarPath-}" ]; then
    mkdir -p "$out/bin"
    binaryPath="$out/bin/${name-customJdk}"

    substitute @binaryTemplate@ "$binaryPath" \
      --subst-var-by jar "$jarPath" \
      --subst-var-by jdk "${jdk-$out}" \
      --subst-var javaOpts
    chmod +x "$binaryPath"
  fi

  runHook postInstall

  echo "Finished customJdkInstallHook"
}

if [ -z "${dontCustomJdkHook-}" ] && [ -z "${installPhase-}" ]; then
  installPhase=customJdkInstallHook
fi
