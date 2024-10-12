{ lib
, stdenv
, fetchurl
, fetchzip
, fetchsvn
, fetchgit
, fetchfossil
, fetchcvs
, fetchhg
, fetchFromGitea
, fetchFromGitHub
, fetchFromGitLab
, fetchFromGitiles
, fetchFromBitbucket
, fetchFromSavannah
, fetchFromRepoOrCz
, fetchFromSourcehut
}:

srcJsonPath:


let
  info = lib.importJSON (srcJsonPath + /info.json);
  src = info.src;
  fetcher = src.fetcher;
  args = builtins.removeAttrs src [ "fetcher" ];


  finalSrc =
    if fetcher == "fetchurl" then fetchurl args
    else if fetcher == "fetchzip" then fetchzip args
    else if fetcher == "fetchsvn" then fetchsvn args
    else if fetcher == "fetchgit" then fetchgit args
    else if fetcher == "fetchfossil" then fetchfossil args
    else if fetcher == "fetchcvs" then fetchcvs args
    else if fetcher == "fetchhg" then fetchhg args
    else if fetcher == "fetchFromGitea" then fetchFromGitea args
    else if fetcher == "fetchFromGitHub" then fetchFromGitHub args
    else if fetcher == "fetchFromGitLab" then fetchFromGitLab args
    else if fetcher == "fetchFromGitiles" then fetchFromGitiles args
    else if fetcher == "fetchFromBitbucket" then fetchFromBitbucket args
    else if fetcher == "fetchFromSavannah" then fetchFromSavannah args
    else if fetcher == "fetchFromRepoOrCz" then fetchFromRepoOrCz args
    else if fetcher == "fetchFromSourcehut" then fetchFromSourcehut args
    else abort "Invalid fetcher: ${fetcher}";


in

{
  src = finalSrc;
  inherit (info) name version;
}
