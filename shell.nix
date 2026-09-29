# Classic nix-shell entry point, for machines/tools that don't use flakes.
# `nix-shell` alone gets you a JDK on PATH with JAVA_HOME set correctly.
{ pkgs ? import <nixpkgs> { } }:

pkgs.mkShell {
  packages = [ pkgs.jdk21 ];
  JAVA_HOME = pkgs.jdk21.home;
}
