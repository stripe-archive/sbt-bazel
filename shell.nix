{ pkgs ? import <nixpkgs> {} }:
with pkgs;

stdenv.mkDerivation rec {
  name = "env";

  src = builtins.filterSource (path: type: false) ./.;

  buildInputs = [
    sbt bazel
  ];

  installPhase = ''
    mkdir -p $out
    ln -s ${bazel}/bin/bazel $out/bazel
  '';
}
