{ runtimeShell, writeText }:
{
  binaryTemplate =
    writeText "template" ''
      #!${runtimeShell}

      exec "@jdk@/bin/java" @javaOpts@ \
          -jar "@jar@" "$@"
    '';
}
