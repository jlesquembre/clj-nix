{ config, options, lib, pkgs, ... }:
let types = lib.types; in
{
  options = {

    jdk = lib.mkOption {
      type = types.package;
      default = pkgs.jdk_headless;
      defaultText = lib.literalExpression "pkgs.jdk_headless";
      description = lib.mdDoc "JDK used to build and run the application.";
    };

    projectSrc = lib.mkOption {
      type = types.path;
      description = lib.mdDoc "Project source code.";
      example = lib.literalExpression "./.";
    };

    name = lib.mkOption {
      type = types.str;
      description = lib.mdDoc "Name of the clojure project. Needs to include the namespace.";
      example = "myorg/project";
    };

    version = lib.mkOption {
      default = "DEV";
      type = types.str;
      description = lib.mdDoc "Derivation and clojure project version";
    };

    main-ns = lib.mkOption {
      type = types.str;
      description = lib.mdDoc
        ''
          Main clojure namespace. A `-main` function is expected here.
          `main-ns` file must include the :gen-class directive, e.g.: `(ns foo (:gen-class))`
        '';
    };

    java-opts = lib.mkOption {
      type = types.listOf types.str;
      default = [ ];
      description = lib.mdDoc "List of Java options to include in the application wrapper";
    };

    buildCommand = lib.mkOption {
      default = null;
      type = types.nullOr types.str;
      description = "Command to build the jar application. If not provided, a default builder is used";
    };

    lockfile = lib.mkOption {
      default = null;
      type = types.nullOr types.str;
      description = lib.mdDoc "The lock file";
    };

    compileCljOpts = lib.mkOption {
      default = null;
      description = lib.mdDoc "Override options passed to compile-clj";
      type = types.anything;
    };

    javacOpts = lib.mkOption {
      default = null;
      description = lib.mdDoc "Options passed to javac. Needed if the projects contains java source files";
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
      description = lib.mdDoc "Enable it to invoke leiningen during the build";
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
            description = lib.mdDoc ''
              Creates a custom JDK runtime with `jlink`.
            '';
          };

          jdkModules = lib.mkOption {
            default = null;
            type = types.nullOr types.str;
            description = lib.mdDoc ''
              Option passed to `jlink --add-modules`.
              If `null`, `jdeps` will be used to analyze the uberjar'';
          };

          extraJdkModules = lib.mkOption {
            default = [ ];
            type = types.listOf types.str;
            description = lib.mdDoc "Extra JDK modules appended to `jdkModules`";
          };

          locales = lib.mkOption {
            default = null;
            type = types.nullOr types.str;
            description = lib.mdDoc "Option passed to `jlink --include-locales`";
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
            description = lib.mdDoc "Generates a binary with GraalVM";
          };

          graalvm = lib.mkOption {
            default = pkgs.graalvmCEPackages.graalvm19-ce;
            defaultText = lib.literalExpression "pkgs.graalvmCEPackages.graalvm19-ce";
            type = types.package;
            description = lib.mdDoc "GraalVM used at build time";
          };

          extraNativeImageBuildArgs = lib.mkOption {
            default = [ ];
            type = types.listOf types.str;
            description = lib.mdDoc "Extra arguments to be passed to the native-image command";
          };

          graalvmXmx = lib.mkOption {
            default = "-J-Xmx6g";
            type = types.str;
            description = lib.mdDoc "XMX size of GraalVM during build";
          };

        };
      };
    };


  };
}
