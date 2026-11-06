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
        androidAbi = if pkgs.stdenv.hostPlatform.isAarch64 then "arm64-v8a" else "x86_64";

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
          abiVersions = [ androidAbi ];
        };
        androidHome = "${android.androidsdk}/libexec/android-sdk";
        startEmulator = pkgs.writeShellApplication {
          name = "start-emulator";
          runtimeInputs = [
            android.androidsdk
            pkgs.coreutils
            pkgs.gnused
          ];
          text = ''
            export JAVA_HOME="${jdk}"
            export ANDROID_HOME="${androidHome}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export ANDROID_USER_HOME="''${WORDBYWORD_ANDROID_HOME:-''${XDG_DATA_HOME:-$HOME/.local/share}/wordbyword/android}"
            export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

            avdName="wordbyword-api-${platformVersion}-${androidAbi}"
            while read -r serial status; do
              if [[ "$serial" == emulator-* && "$status" == device ]]; then
                runningName=$(adb -s "$serial" emu avd name | head -n 1 | tr -d '\r')
                if [[ "$runningName" == "$avdName" ]]; then
                  echo "WordByWord emulator is already running: $serial"
                  exit 0
                fi
              fi
            done < <(adb devices)

            mkdir -p "$ANDROID_AVD_HOME"
            avdDir="$ANDROID_AVD_HOME/$avdName.avd"
            if [[ ! -f "$avdDir/config.ini" ]]; then
              printf 'no\n' | avdmanager create avd \
                --name "$avdName" \
                --package "system-images;android-${platformVersion};google_apis;${androidAbi}" \
                --path "$avdDir"
            fi

            set_avd_property() {
              local key="$1" value="$2"
              local escapedKey="''${key//./\\.}"
              sed "/^$escapedKey=/d" "$avdDir/config.ini" > "$avdDir/config.ini.tmp"
              printf '%s=%s\n' "$key" "$value" >> "$avdDir/config.ini.tmp"
              mv "$avdDir/config.ini.tmp" "$avdDir/config.ini"
            }

            set_avd_property image.sysdir.1 "$ANDROID_HOME/system-images/android-${platformVersion}/google_apis/${androidAbi}/"
            set_avd_property hw.lcd.width 1080
            set_avd_property hw.lcd.height 1920
            set_avd_property hw.lcd.density 420
            set_avd_property hw.ramSize 2048
            set_avd_property vm.heapSize 256
            set_avd_property hw.keyboard yes
            set_avd_property hw.mainKeys no
            set_avd_property skin.name 1080x1920
            set_avd_property skin.path 1080x1920
            set_avd_property showDeviceFrame no

            echo "WordByWord: Android ${platformVersion}, 1080x1920, 420 dpi"
            exec emulator -avd "$avdName" -gpu auto -no-snapshot "$@"
          '';
        };
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
            echo "Emulator: nix run .#start-emulator"
          '';
        };
        apps.start-emulator = {
          type = "app";
          program = "${startEmulator}/bin/start-emulator";
        };
        formatter = pkgs.nixfmt;
      }
    );
}
