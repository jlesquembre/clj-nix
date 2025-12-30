{
  config,
  options,
  lib,
  pkgs,
  ...
}:
let
  types = lib.types;
in
{
  options = {

    jdk = lib.mkOption {
      type = types.package;
      default = pkgs.jdk_headless;
      defaultText = lib.literalExpression "pkgs.jdk_headless";
      description = "JDK used to build and run the application.";
    };

    projectSrc = lib.mkOption {
      type = types.path;
      description = "Project source code.";
      example = lib.literalExpression "./.";
    };

    name = lib.mkOption {
      type = types.str;
      description = "Name of the clojure project. Needs to include the namespace.";
      example = "myorg/project";
    };

    version = lib.mkOption {
      default = "DEV";
      type = types.str;
      description = "Derivation and clojure project version";
    };

    main-ns = lib.mkOption {
      type = types.str;
      description = ''
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

    builder-extra-inputs = lib.mkOption {
      default = [ ];
      type = types.listOf types.package;
      description = "Extra inputs to the default builder";
    };

    builder-java-opts = lib.mkOption {
      type = types.listOf types.str;
      default = [ ];
      description = "List of Java options to include in default builder command";
    };

    builder-preBuild = lib.mkOption {
      default = "";
      type = types.str;
      description = "Pre build commands for the default builder";
    };
    builder-postBuild = lib.mkOption {
      default = "";
      type = types.str;
      description = "Post build commands for the default builder";
    };

    lockfile = lib.mkOption {
      default = null;
      type = types.nullOr types.path;
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

    uberOpts = lib.mkOption {
      default = null;
      description = "Options passed to uber.";
      type = types.nullOr (
        (types.submodule {
          options.exclude = lib.mkOption {
            type = types.listOf types.str;
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
              Creates a custom JDK runtime with `jlink`.
            '';
          };

          jdkModules = lib.mkOption {
            default = null;
            type = types.nullOr types.str;
            description = ''
              Option passed to `jlink --add-modules`.
              If `null`, `jdeps` will be used to analyze the uberjar'';
          };

          extraJdkModules = lib.mkOption {
            default = [ ];
            type = types.listOf types.str;
            description = "Extra JDK modules appended to `jdkModules`";
          };

          locales = lib.mkOption {
            default = null;
            type = types.nullOr types.str;
            description = "Option passed to `jlink --include-locales`";
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
            default = pkgs.graalvmPackages.graalvm-ce;
            defaultText = lib.literalExpression "pkgs.graalvmPackages.graalvm-ce";
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

          static = lib.mkOption {
            default = false;
            type = types.bool;
            description = "Build a static binary using musl libc";
          };

        };
      };
    };

  };
}
