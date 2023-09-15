{ lib
, makeSetupHook
, unzip
, gnugrep
, writeText
, common
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
        inherit (common) binaryTemplate;
      };
    } ./custom-jdk-install-hook.sh;
}
