{ stdenv, python311Packages, pkgs, ... }:

let
  inherit (pkgs) lib;

  eval = lib.evalModules {
    modules = [
      ../../modules/top-level.nix
    ];
    specialArgs = { inherit pkgs; };
  };

  rewriteSource = decl:
    let
      prefix = lib.strings.concatStringsSep "/" (lib.lists.take 4 (lib.strings.splitString "/" decl));
      path = lib.strings.removePrefix prefix decl;
      url = "https://github.com/jlesquembre/clj-nix/blob/main${path}";
    in
    { name = url; url = url; };

  options = pkgs.nixosOptionsDoc {
    options = builtins.removeAttrs eval.options [ "_module" ];
    warningsAreErrors = false;
    transformOptions = opt: (
      opt // { declarations = map rewriteSource opt.declarations; }
    );
  };
in


stdenv.mkDerivation {
  pname = "omics-platform-docs";
  version = "1.0.0";

  src = ../../.;

  buildInputs = with  python311Packages; [ mkdocs-material mkdocs ];

  buildPhase = ''
    mv extra-pkgs/docs/mkdocs.yaml .
    cp ${options.optionsCommonMark} docs/options.md
    mkdocs build
  '';

  installPhase = ''
    mv site $out
  '';
}
