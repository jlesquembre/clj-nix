{ lib
, makeSetupHook
, unzip
, gnugrep
, runtimeShell
, writeText
}:

{
  customJdkInstallHook = makeSetupHook
    {
      name = "custom-jdk-install-hook";
      propagatedBuildInputs = [
        unzip
        gnugrep
      ];
      substitutions = {
        binaryTemplate = writeText "template" ''
          #!${runtimeShell}

          exec "@jdk@/bin/java" \
              -jar "@jar@" "$@"
        '';
      };
    } ./custom-jdk-install-hook.sh;
}
