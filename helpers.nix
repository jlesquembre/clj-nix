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

                  jdkRunner = lib.mkOption {
                    type = types.package;
                    default = pkgs.jdk_headless;
                    description = "JDK used at runtime by the application.";
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
                };
              })

          ] ++ modules;
      };

      cfg = _m.config;

      cljDrv = pkgs'.mkCljBin {
        inherit (cfg) jdkRunner projectSrc name version main-ns buildCommand
          lockfile java-opts compileCljOpts javacOpts;
      };
    in

    assert
    (
      pkgs'.lib.assertMsg
        (! cfg.customJdk.enable)
        ''
          customJdk enabled!
        ''
    );

    if cfg.customJdk.enable then
      pkgs'.customJdk
        {
          inherit cljDrv;
          jdkBase = cfg.jdkRunner;
          inherit (cfg.customJdk) jdkModules extraJdkModules locales;
        }
    else
      cljDrv;

}
