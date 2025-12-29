{
  description = "Audiobookshelf Android App Dev Environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs.url = "github:tadfisher/android-nixpkgs";
  };

  outputs = { self, nixpkgs, android-nixpkgs }:
    let
      system = "aarch64-darwin";
      pkgs = import nixpkgs {
        inherit system;
        config.android_sdk.accept_license = true;
      };

      androidSdk = android-nixpkgs.sdk.${system} (sdkPkgs: with sdkPkgs; [
        build-tools-35-0-0
        platforms-android-35
        platform-tools
        cmdline-tools-latest
      ]);

    in {
      devShells.${system}.default = pkgs.mkShell {
        buildInputs = [
          pkgs.nodejs_20
          pkgs.jdk21
          androidSdk
        ];

        JAVA_HOME = pkgs.jdk21;
        ANDROID_HOME = "{androidSdk}/share/android-sdk";
      };
    };
}
