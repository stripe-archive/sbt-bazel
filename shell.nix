{ pkgs ? import <nixpkgs> {} }:

with pkgs;

let
  bazel = (import (fetchFromGitHub {
    owner = "andyscott";
    repo = "bazel-nix";
    rev = "ab5eac78f0b9e49f43e14b4266bc0401f85f4e46";
    sha256 = "1xn8gld84hrqjdbp7zxqp65ygkrvxjvlhsg6hkj8wd4ma1xl5aqd";
  })) {
    version = "0.28.1";
  };

in
  stdenv.mkDerivation {
    name = "commands-nix";
    buildInputs = [ bazel sbt llvmPackages.stdenv ];
  }
