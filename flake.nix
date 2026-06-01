{
  description = "Flake to manage wasmts builds";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in {
        # mkShellNoCC, not mkShell: this project compiles no C, and mkShell's stdenv
        # cc wrapper shadows the host toolchain that native-image insists on. With
        # nix clang first on PATH, `mvn package` dies at [1/8] Initializing with
        # "Unable to detect supported DARWIN native software development toolchain".
        devShells.default = pkgs.mkShellNoCC {
          buildInputs = with pkgs; [
            babashka
            clojure
            maven
            nodejs
            # mx, which drives the graal submodule build, is a Python program.
            python3
            # scripts/build-graal.sh drives the graal and mx submodules.
            git
            coreutils
            findutils
          ];
          shellHook = ''
            # mvn's javac needs a plain JDK 21+ carrying no org.graalvm.webimage.api
            # module, or it split-package clashes with the webimage.api sources that
            # build-helper vendors in (see pom.xml). native-image comes from
            # graal-home regardless of JAVA_HOME. Prefer the labsjdk that
            # scripts/build-graal.sh fetches, so javac runs on the same JDK graal
            # itself was built from; fall back to nixpkgs so a fresh clone can run
            # `bb test` before graal has ever been built.
            if [ -x "$PWD/.jdks/labsjdk-ce-latest/Contents/Home/bin/java" ]; then
              export JAVA_HOME="$PWD/.jdks/labsjdk-ce-latest/Contents/Home"
            elif [ -x "$PWD/.jdks/labsjdk-ce-latest/bin/java" ]; then
              export JAVA_HOME="$PWD/.jdks/labsjdk-ce-latest"
            else
              export JAVA_HOME="${pkgs.jdk21}"
            fi
            export PATH="$JAVA_HOME/bin:$PATH"
          '';
        };
      });
}
