{
  description = "TextBridge packages, NixOS module, and development shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
      pkgsFor = system:
        import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
          };
        };
      mkInstallAndroidSkills = pkgs:
        let
          androidSkillsSource = pkgs.fetchFromGitHub {
            owner = "android";
            repo = "skills";
            rev = "fe95b6fdf002ece6c7340013623d41d8deec6f52";
            hash = "sha256-fMbe2E45VtFvIzh6HYKZ1Mqc/E5yc5Ox5jS3JwnVpuE=";
          };
        in
        pkgs.writeShellApplication {
          name = "install-android-skills";
          runtimeInputs = with pkgs; [
            coreutils
            findutils
            gawk
          ];
          text = ''
            set -euo pipefail

            root="''${1:-$PWD}"
            skills_root="$root/.agents/skills"
            android_skills_source="${androidSkillsSource}"
            managed_manifest="$skills_root/.android-skills-managed"

            install_skill() {
              local target_name="$1"
              local source="$2"
              local target="$skills_root/$target_name"
              local current=""

              if [ ! -d "$source" ]; then
                echo "Skill source does not exist: $source" >&2
                return 1
              fi

              if [ ! -f "$source/SKILL.md" ]; then
                echo "Skill source is missing SKILL.md: $source" >&2
                return 1
              fi

              if [ -e "$target" ] || [ -L "$target" ]; then
                current="$(readlink "$target" || true)"
                if [ "$current" = "$source" ]; then
                  return 0
                fi

                if [ -L "$target" ] && { [[ "$current" == /nix/store/* ]] || [[ "$current" == "$root"/skills/local/* ]]; }; then
                  rm -f "$target"
                else
                  echo "Skill target already exists and is not managed: $target" >&2
                  return 1
                fi
              fi

              mkdir -p "$(dirname "$target")"
              ln -s "$source" "$target"
            }

            is_managed_target() {
              local target="$1"
              local current=""

              if [ ! -L "$target" ]; then
                return 1
              fi

              current="$(readlink "$target" || true)"
              [[ "$current" == /nix/store/* ]] || [[ "$current" == "$root"/skills/local/* ]]
            }

            prune_old_official_skills() {
              local target_name=""
              local target=""

              if [ ! -f "$managed_manifest" ]; then
                return 0
              fi

              while IFS= read -r target_name; do
                if [ -z "$target_name" ]; then
                  continue
                fi

                case "$target_name" in
                  .* | */* | *[!A-Za-z0-9._-]*)
                    echo "Skipping unsafe managed skill name: $target_name" >&2
                    continue
                    ;;
                esac

                target="$skills_root/$target_name"
                if is_managed_target "$target"; then
                  rm -f "$target"
                fi
              done < "$managed_manifest"
            }

            read_skill_name() {
              local skill_file="$1"

              awk '
                NR == 1 && $0 == "---" {
                  in_frontmatter = 1
                  next
                }
                in_frontmatter && $0 == "---" {
                  exit
                }
                in_frontmatter && $0 ~ /^name:[[:space:]]*/ {
                  sub(/^name:[[:space:]]*/, "")
                  print
                  exit
                }
              ' "$skill_file"
            }

            install_official_skills() {
              local next_manifest=""
              local skill_file=""
              local skill_dir=""
              local skill_name=""

              next_manifest="$(mktemp "''${TMPDIR:-/tmp}/android-skills-managed.XXXXXX")"
              while IFS= read -r skill_file; do
                skill_dir="$(dirname "$skill_file")"
                skill_name="$(read_skill_name "$skill_file")"

                case "$skill_name" in
                  "" | .* | */* | *[!A-Za-z0-9._-]*)
                    echo "Could not determine safe skill name for $skill_file: $skill_name" >&2
                    rm -f "$next_manifest"
                    return 1
                    ;;
                esac

                install_skill "$skill_name" "$skill_dir"
                printf '%s\n' "$skill_name" >> "$next_manifest"
              done < <(find "$android_skills_source" -type f -name SKILL.md | sort)

              mv "$next_manifest" "$managed_manifest"
            }

            mkdir -p "$skills_root"
            prune_old_official_skills
            install_skill android-dev "$root/skills/local/android-dev"
            install_official_skills
            echo "Android skills installed into $skills_root"
          '';
        };
    in
    {
      packages = forAllSystems (system:
        let
          pkgs = pkgsFor system;
          textbridgePython = pkgs.python3.withPackages (ps: with ps; [
            dbus-python
            pygobject3
          ]);
        in
        {
          textbridge-server = pkgs.stdenvNoCC.mkDerivation {
            pname = "textbridge-server";
            version = "0.1.0";
            src = ./textbridge/desktop/server;
            dontConfigure = true;
            dontBuild = true;
            doCheck = true;
            nativeBuildInputs = [
              textbridgePython
            ];

            checkPhase = ''
              runHook preCheck
              python3 test_textbridge_server.py
              python3 test_textbridge_bluetooth_server.py
              runHook postCheck
            '';

            installPhase = ''
              runHook preInstall

              mkdir -p $out/bin
              mkdir -p $out/lib/textbridge
              mkdir -p $out/share/systemd/user
              mkdir -p $out/share/doc/textbridge

              cp textbridge_server.py $out/lib/textbridge/textbridge_server.py
              cp textbridge_bluetooth_server.py $out/lib/textbridge/textbridge_bluetooth_server.py
              cp config.example.json $out/share/doc/textbridge/config.example.json
              cp textbridge-server.service $out/share/systemd/user/textbridge-server.service
              cp textbridge-bluetooth-server.service $out/share/systemd/user/textbridge-bluetooth-server.service

              patchShebangs $out/lib/textbridge/textbridge_server.py
              patchShebangs $out/lib/textbridge/textbridge_bluetooth_server.py
              chmod +x $out/lib/textbridge/textbridge_server.py
              chmod +x $out/lib/textbridge/textbridge_bluetooth_server.py
              ln -s $out/lib/textbridge/textbridge_server.py $out/bin/textbridge-server
              ln -s $out/lib/textbridge/textbridge_bluetooth_server.py $out/bin/textbridge-bluetooth-server
              substituteInPlace $out/share/systemd/user/textbridge-server.service \
                --replace-fail "%h/.local/lib/textbridge/textbridge_server.py" \
                "$out/bin/textbridge-server"
              substituteInPlace $out/share/systemd/user/textbridge-bluetooth-server.service \
                --replace-fail "%h/.local/lib/textbridge/textbridge_bluetooth_server.py" \
                "$out/bin/textbridge-bluetooth-server"

              runHook postInstall
            '';
          };

          textbridge-adb-connect = pkgs.stdenvNoCC.mkDerivation {
            pname = "textbridge-adb-connect";
            version = "0.1.0";
            src = ./textbridge/tools;
            dontConfigure = true;
            dontBuild = true;
            doCheck = true;
            nativeBuildInputs = [
              pkgs.gawk
              pkgs.makeWrapper
              pkgs.python3
            ];

            checkPhase = ''
              runHook preCheck
              chmod +x textbridge-adb-connect test_textbridge_adb_connect.py
              patchShebangs textbridge-adb-connect test_textbridge_adb_connect.py
              python3 test_textbridge_adb_connect.py
              runHook postCheck
            '';

            installPhase = ''
              runHook preInstall

              mkdir -p $out/bin
              cp textbridge-adb-connect $out/bin/textbridge-adb-connect
              patchShebangs $out/bin/textbridge-adb-connect
              chmod +x $out/bin/textbridge-adb-connect
              wrapProgram $out/bin/textbridge-adb-connect \
                --prefix PATH : ${pkgs.lib.makeBinPath [
                  pkgs.android-tools
                  pkgs.gawk
                ]}

              runHook postInstall
            '';
          };

          fcitx5-textbridge = pkgs.stdenv.mkDerivation {
            pname = "fcitx5-textbridge";
            version = "0.1.0";
            src = ./textbridge/desktop/fcitx5-addon;

            nativeBuildInputs = [
              pkgs.cmake
              pkgs.ninja
              pkgs.pkg-config
            ];

            buildInputs = [
              pkgs.fcitx5
            ];

            cmakeFlags = [
              "-GNinja"
            ];
          };

          default = self.packages.${system}.textbridge-server;
        });

      checks = forAllSystems (system: {
        textbridge-server = self.packages.${system}.textbridge-server;
        textbridge-adb-connect = self.packages.${system}.textbridge-adb-connect;
        fcitx5-textbridge = self.packages.${system}.fcitx5-textbridge;
      });

      apps = forAllSystems (system: {
        textbridge-server = {
          type = "app";
          program = "${self.packages.${system}.textbridge-server}/bin/textbridge-server";
          meta = {
            description = "Run the TextBridge Wi-Fi HTTP server";
          };
        };
        textbridge-adb-connect = {
          type = "app";
          program = "${self.packages.${system}.textbridge-adb-connect}/bin/textbridge-adb-connect";
          meta = {
            description = "Create an adb reverse tunnel for TextBridge USB/ADB mode";
          };
        };
        textbridge-bluetooth-server = {
          type = "app";
          program = "${self.packages.${system}.textbridge-server}/bin/textbridge-bluetooth-server";
          meta = {
            description = "Run the TextBridge Bluetooth RFCOMM server";
          };
        };
      });

      nixosModules =
        let
          serverModule = { config, lib, pkgs, ... }:
          let
            cfg = config.services.textbridge.server;
            bluetoothCfg = config.services.textbridge.bluetooth;
            system = pkgs.stdenv.hostPlatform.system;
            serverConfig = pkgs.writeText "textbridge-server.json" (builtins.toJSON ({
              listen_host = cfg.listenHost;
              listen_port = cfg.port;
              token_file = cfg.tokenFile;
              max_text_bytes = cfg.maxTextBytes;
              request_timeout_ms = cfg.requestTimeoutMs;
              discovery_enabled = cfg.discovery.enable;
              discovery_port = cfg.discovery.port;
              device_name = cfg.deviceName;
            } // lib.optionalAttrs (cfg.runtimeDir != null) {
              runtime_dir = cfg.runtimeDir;
            } // lib.optionalAttrs (cfg.fcitxSocket != null) {
              fcitx_socket = cfg.fcitxSocket;
            }));
          in
          {
            options.services.textbridge.server = {
              enable = lib.mkEnableOption "TextBridge Wi-Fi desktop server";

              package = lib.mkOption {
                type = lib.types.package;
                default = self.packages.${system}.textbridge-server;
                defaultText = lib.literalExpression "inputs.textbridge.packages.\${pkgs.system}.textbridge-server";
                description = "Package providing the TextBridge HTTP server.";
              };

              adbHelper = {
                enable = lib.mkOption {
                  type = lib.types.bool;
                  default = true;
                  description = "Whether to install the textbridge-adb-connect helper for USB/ADB transport.";
                };

                package = lib.mkOption {
                  type = lib.types.package;
                  default = self.packages.${system}.textbridge-adb-connect;
                  defaultText = lib.literalExpression "inputs.textbridge.packages.\${pkgs.system}.textbridge-adb-connect";
                  description = "Package providing the textbridge-adb-connect helper.";
                };
              };

              tokenFile = lib.mkOption {
                type = lib.types.nullOr lib.types.str;
                default = null;
                description = "Path to a file containing the TextBridge bearer token. This value is read at runtime and must not point into the Nix store.";
              };

              listenHost = lib.mkOption {
                type = lib.types.str;
                default = "0.0.0.0";
                description = "Address the HTTP server listens on.";
              };

              port = lib.mkOption {
                type = lib.types.port;
                default = 17321;
                description = "TCP port for the TextBridge HTTP commit API.";
              };

              maxTextBytes = lib.mkOption {
                type = lib.types.ints.positive;
                default = 16 * 1024;
                description = "Maximum UTF-8 text payload size accepted by the server.";
              };

              requestTimeoutMs = lib.mkOption {
                type = lib.types.ints.positive;
                default = 2000;
                description = "Timeout in milliseconds while waiting for the Fcitx addon response.";
              };

              runtimeDir = lib.mkOption {
                type = lib.types.nullOr lib.types.str;
                default = null;
                description = "Optional runtime directory for the Fcitx Unix datagram socket.";
              };

              fcitxSocket = lib.mkOption {
                type = lib.types.nullOr lib.types.str;
                default = null;
                description = "Optional path to the Fcitx addon Unix datagram socket.";
              };

              deviceName = lib.mkOption {
                type = lib.types.str;
                default = config.networking.hostName;
                defaultText = lib.literalExpression "config.networking.hostName";
                description = "Device name advertised in discovery responses.";
              };

              enableUserService = lib.mkOption {
                type = lib.types.bool;
                default = true;
                description = "Whether to install and enable the TextBridge user service.";
              };

              openFirewall = lib.mkOption {
                type = lib.types.bool;
                default = true;
                description = "Whether to open the configured HTTP and discovery ports in the NixOS firewall.";
              };

              discovery = {
                enable = lib.mkOption {
                  type = lib.types.bool;
                  default = true;
                  description = "Whether to enable UDP LAN discovery.";
                };

                port = lib.mkOption {
                  type = lib.types.port;
                  default = 17322;
                  description = "UDP port used for TextBridge LAN discovery.";
                };
              };
            };

            options.services.textbridge.bluetooth = {
              enable = lib.mkEnableOption "TextBridge Bluetooth RFCOMM server";

              package = lib.mkOption {
                type = lib.types.package;
                default = self.packages.${system}.textbridge-server;
                defaultText = lib.literalExpression "inputs.textbridge.packages.\${pkgs.system}.textbridge-server";
                description = "Package providing the TextBridge Bluetooth server.";
              };

              enableUserService = lib.mkOption {
                type = lib.types.bool;
                default = true;
                description = "Whether to install and enable the TextBridge Bluetooth user service.";
              };
            };

            config = lib.mkMerge [
              (lib.mkIf (cfg.enable || bluetoothCfg.enable) {
                assertions = [
                  {
                    assertion = cfg.tokenFile != null;
                    message = ''
                      services.textbridge.server.tokenFile must be set.
                      Generate a token with:
                        python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
                      Then provide it through sops-nix, for example:
                        services.textbridge.server.tokenFile = config.sops.secrets."textbridge-token".path;
                    '';
                  }
                  {
                    assertion = cfg.tokenFile == null || !(lib.hasPrefix "/nix/store/" cfg.tokenFile);
                    message = "services.textbridge.server.tokenFile must not point into the Nix store.";
                  }
                ];

                environment.systemPackages =
                  lib.optionals cfg.enable ([ cfg.package ] ++ lib.optional cfg.adbHelper.enable cfg.adbHelper.package)
                  ++ lib.optional bluetoothCfg.enable bluetoothCfg.package;
              })

              (lib.mkIf cfg.enable {
                networking.firewall = lib.mkIf cfg.openFirewall {
                  allowedTCPPorts = [ cfg.port ];
                  allowedUDPPorts = lib.mkIf cfg.discovery.enable [ cfg.discovery.port ];
                };

                systemd.user.services.textbridge-server = lib.mkIf cfg.enableUserService {
                  description = "TextBridge Wi-Fi server";
                  after = [ "network-online.target" ];
                  wantedBy = [ "default.target" ];
                  serviceConfig = {
                    Type = "simple";
                    ExecStart = "${cfg.package}/bin/textbridge-server --config ${serverConfig}";
                    Restart = "on-failure";
                    RestartSec = 2;
                  };
                };
              })

              (lib.mkIf bluetoothCfg.enable {
                hardware.bluetooth.enable = lib.mkDefault true;

                systemd.user.services.textbridge-bluetooth-server = lib.mkIf bluetoothCfg.enableUserService {
                  description = "TextBridge Bluetooth server";
                  after = [ "bluetooth.target" ];
                  wantedBy = [ "default.target" ];
                  serviceConfig = {
                    Type = "simple";
                    ExecStart = "${bluetoothCfg.package}/bin/textbridge-bluetooth-server --config ${serverConfig}";
                    Restart = "on-failure";
                    RestartSec = 2;
                  };
                };
              })
            ];
          };
        in
        {
          server = serverModule;
          textbridge = serverModule;
          default = serverModule;
        };

      devShells = forAllSystems (system:
        let
          pkgs = pkgsFor system;
          androidPkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          androidComposition = androidPkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37" ];
            buildToolsVersions = [ "37.0.0" ];
            includeEmulator = false;
            includeSystemImages = false;
          };
          androidSdk = androidComposition.androidsdk;
          installAndroidSkills = mkInstallAndroidSkills androidPkgs;
        in
        {
          server = pkgs.mkShell {
            packages = [
              pkgs.python3
            ];
          };

          desktop = pkgs.mkShell {
            packages = [
              pkgs.python3
              pkgs.cmake
              pkgs.ninja
              pkgs.pkg-config
              pkgs.fcitx5
            ];
          };

          android = androidPkgs.mkShell {
            packages = [
              androidPkgs.python3
              androidPkgs.cmake
              androidPkgs.ninja
              androidPkgs.pkg-config
              androidPkgs.fcitx5
              androidPkgs.android-cli
              androidPkgs.gradle_9
              androidPkgs.jdk17
              androidPkgs.android-tools
              androidPkgs.jadx
              androidPkgs.kotlin
              androidPkgs.kotlin-language-server
              androidPkgs.ktlint
              androidPkgs.protobuf
              androidPkgs.ripgrep
              androidPkgs.scrcpy
              installAndroidSkills
              androidSdk
            ];

            TEXTBRIDGE_NIX_ANDROID_SDK = "${androidSdk}/libexec/android-sdk";
            TEXTBRIDGE_REQUIRED_ANDROID_PLATFORM = "android-37.0";
            TEXTBRIDGE_REQUIRED_ANDROID_BUILD_TOOLS = "37.0.0";
            JAVA_HOME = androidPkgs.jdk17.home;

            shellHook = ''
              external_android_sdk="''${ANDROID_HOME:-''${ANDROID_SDK_ROOT:-''${XDG_LIB_HOME:-$HOME/Lib}/Android/Sdk}}"
              if [ -d "$external_android_sdk/platforms/$TEXTBRIDGE_REQUIRED_ANDROID_PLATFORM" ] && [ -d "$external_android_sdk/build-tools/$TEXTBRIDGE_REQUIRED_ANDROID_BUILD_TOOLS" ]; then
                export ANDROID_HOME="$external_android_sdk"
                android_sdk_source="Android Studio SDK"
              else
                export ANDROID_HOME="$TEXTBRIDGE_NIX_ANDROID_SDK"
                android_sdk_source="Nix Android SDK"
              fi
              export ANDROID_SDK_ROOT="$ANDROID_HOME"
              export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/$TEXTBRIDGE_REQUIRED_ANDROID_BUILD_TOOLS:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
              ${installAndroidSkills}/bin/install-android-skills "$PWD"
              echo "TextBridge dev shell"
              echo "  Android SDK: $android_sdk_source at $ANDROID_HOME"
              echo "  Android:  cd textbridge/android && gradle assembleDebug"
              echo "  Server:   python3 textbridge/desktop/server/textbridge_server.py --init-config"
              echo "  Fcitx5:   cmake -S textbridge/desktop/fcitx5-addon -B build/fcitx5-addon -GNinja"
            '';
          };

          default = self.devShells.${system}.android;
        });
    };
}
