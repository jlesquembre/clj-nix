let

  formatArg =
    x:
    if x == null then
      [ ]
    else
      if (builtins.isList x) then
        x else [ x ];
in

{ clj-nix_overlay }:
{
  mkCljCli =
    { jdkDrv
    , java-opts ? [ ]
    , extra-args ? [ ]
    }:
    builtins.filter
      (s: builtins.stringLength s != 0)
      (
        [
          "${jdkDrv.jdk}/bin/java"
        ]
        ++ (formatArg java-opts) ++
        [
          "-jar"
          "${jdkDrv.jarPath}"
        ]
        ++ (formatArg extra-args)
      );

  mkCljApp = { pkgs, modules }:
    let
      pkgs' = pkgs.extend clj-nix_overlay;

      _m = pkgs.lib.evalModules {
        specialArgs = { pkgs = pkgs'; };
        modules =
          [
            ({ config, options, lib, mkCljBin, ... }:
              let types = lib.types; in
              {
                options = {

                  jdk = lib.mkOption {
                    type = types.package;
                    default = pkgs'.jdk_headless;
                    description = "JDK used to build and run the application.";
                  };

                  projectSrc = lib.mkOption {
                    type = types.path;
                    description = "Project source code.";
                  };

                  name = lib.mkOption {
                    type = types.str;
                    description = "Name of the clojure project. Needs to include the namespace, e.g.: 'myorg/project'";
                  };

                  version = lib.mkOption {
                    default = "DEV";
                    type = types.str;
                    description = "Derivation and clojure project version";
                  };

                  main-ns = lib.mkOption {
                    type = types.str;
                    description =
                      ''
                        Main clojure namespace. A `-main` function is expected here.
                        `main-ns` file must include the :gen-class directive, e.g.: `(ns foo (:gen-class))`
                      '';
                  };

                  java-opts = lib.mkOption {
                    type = types.listOf types.str;
                    default = [ ];
                    description = "List of Java options to include in the application wrapper";
                  };

                  buildCommand = lib.mkOption {
                    default = null;
                    type = types.nullOr types.str;
                    description = "Command to build the jar application. If not provided, a default builder is used";
                  };

                  lockfile = lib.mkOption {
                    default = null;
                    type = types.nullOr types.str;
                    description = "The lock file";
                  };

                  compileCljOpts = lib.mkOption {
                    default = null;
                    description = "Override options passed to compile-clj";
                    type = types.anything;
                  };

                  javacOpts = lib.mkOption {
                    default = null;
                    description = "Options passed to javac. Needed if the projects contains java source files";
                    type = types.nullOr (
                      (types.submodule {
                        options.src-dirs = lib.mkOption {
                          type = types.listOf types.str;
                        };
                        options.javac-opts = lib.mkOption {
                          default = [ ];
                          type = types.nullOr (types.listOf types.str);
                        };
                      })
                    );
                  };

                  withLeiningen = lib.mkOption {
                    default = false;
                    description = "Enable it to invoke leiningen during the build";
                    type = types.bool;
                  };

                  ###
                  # Options for customJdk
                  ###

                  customJdk = lib.mkOption {
                    default = { };
                    type = types.submodule {
                      options = {
                        enable = lib.mkOption {
                          default = false;
                          type = types.bool;
                          description = ''
                            Creates a custom JDK runtime with jlink.
                          '';
                        };

                        jdkModules = lib.mkOption {
                          default = null;
                          type = types.nullOr types.str;
                          description = ''
                            Option passed to jlink --add-modules.
                            If null, jeps will be used to analyze the uberjar'';
                        };

                        extraJdkModules = lib.mkOption {
                          default = [ ];
                          type = types.listOf types.str;
                          description = "Extra JDK modules appended to jdkModules";
                        };

                        locales = lib.mkOption {
                          default = null;
                          type = types.nullOr types.str;
                          description = "Option passed to jlink --include-locales";
                        };

                      };
                    };
                  };

                  ###
                  # Options for nativeImage ()
                  ###

                  nativeImage = lib.mkOption {
                    default = { };
                    type = types.submodule {
                      options = {
                        enable = lib.mkOption {
                          default = false;
                          type = types.bool;
                          description = "Generates a binary with GraalVM";
                        };

                        graalvm = lib.mkOption {
                          default = pkgs'.graalvmCEPackages.graalvm19-ce;
                          type = types.package;
                          description = "GraalVM used at build time";
                        };

                        extraNativeImageBuildArgs = lib.mkOption {
                          default = [ ];
                          type = types.listOf types.str;
                          description = "Extra arguments to be passed to the native-image command";
                        };

                        graalvmXmx = lib.mkOption {
                          default = "-J-Xmx6g";
                          type = types.str;
                          description = "XMX size of GraalVM during build";
                        };

                      };
                    };
                  };


                };
              })

          ] ++ modules;
      };

      cfg = _m.config;

      cljDrv = pkgs'.mkCljBin {
        jdkRunner = cfg.jdk;
        inherit (cfg) projectSrc name version main-ns buildCommand
          lockfile java-opts compileCljOpts javacOpts;
      };
    in

    assert (pkgs'.lib.assertMsg
      (cfg.customJdk.enable == true -> cfg.nativeImage.enable == false)
      "customJdk and nativeImage are incompatible, you can enable only one"
    );

    assert (pkgs'.lib.assertMsg
      (cfg.withLeiningen == true -> ! isNull cfg.buildCommand)
      "With Leiningen you have to provide a 'buildCommand'"
    );

    assert (pkgs'.lib.assertMsg
      (cfg.withLeiningen == true -> cfg.compileCljOpts == false && cfg.javacOpts == false)
      "Leiningen is incompatible with Clojure tools.build options (compileCljOpts and javacOpts)"
    );


    if cfg.customJdk.enable then
      pkgs'.customJdk
        {
          inherit cljDrv;
          jdkBase = cfg.jdk;
          inherit (cfg.customJdk) jdkModules extraJdkModules locales;
        }

    else if cfg.nativeImage.enable then
      pkgs'.mkGraalBin
        {
          inherit cljDrv;
          inherit (cfg.nativeImage) graalvm extraNativeImageBuildArgs graalvmXmx;
        }
    else
      cljDrv;

}
