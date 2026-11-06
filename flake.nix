{
  description = "WordByWord development environment";

  inputs = {
    flake-utils.url = "github:numtide/flake-utils";
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
  };

  outputs =
    { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        javaVersion = "21";
        jdk = pkgs."jdk${javaVersion}";
        platformVersion = "36";
        buildToolsVersion = "36.0.0";
        ndkVersion = "27.2.12479018";

        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ platformVersion ];
          buildToolsVersions = [ buildToolsVersion ];
          includeSources = true;
          includeNDK = true;
          inherit ndkVersion;
          cmakeVersions = [ "3.22.1" ];
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "google_apis" ];
          abiVersions = [ (if pkgs.stdenv.hostPlatform.isAarch64 then "arm64-v8a" else "x86_64") ];
        };
        androidHome = "${android.androidsdk}/libexec/android-sdk";
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk
            # Bootstrap tool; use the project's ./gradlew once available.
            (gradle_8.override { java = jdk; })
            (maven.override { jdk_headless = jdk; })
            android.androidsdk

            # Editor support and formatting.
            jdt-language-server
            google-java-format
            nixfmt
            libxml2

            # Native/JNI builds and inspection.
            cmake
            ninja
            pkg-config
            clang-tools

            # Device control, audio fixtures, and database inspection.
            scrcpy
            ffmpeg
            sqlite
          ];

          JAVA_HOME = "${jdk}";
          ANDROID_HOME = androidHome;
          ANDROID_SDK_ROOT = androidHome;
          ANDROID_NDK_ROOT = "${androidHome}/ndk/${ndkVersion}";
          ANDROID_NDK_HOME = "${androidHome}/ndk/${ndkVersion}";

          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidHome}/build-tools/${buildToolsVersion}/aapt2";

          shellHook = ''
            export PATH="${androidHome}/platform-tools:${androidHome}/build-tools/${buildToolsVersion}:${androidHome}/cmdline-tools/latest/bin:$PATH"
            export PATH="${androidHome}/emulator:$PATH"
            echo "WordByWord: Java ${javaVersion}, Android API ${platformVersion}, NDK ${ndkVersion}"
          '';
        };
        formatter = pkgs.nixfmt;
      }
    );
}
