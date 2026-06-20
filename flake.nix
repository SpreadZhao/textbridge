{
  description = "TextBridge desktop packages and NixOS module";

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
    in
    {
      packages = forAllSystems (system:
        let
          pkgs = pkgsFor system;
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
              pkgs.python3
            ];

            checkPhase = ''
              runHook preCheck
              python3 test_textbridge_server.py
              runHook postCheck
            '';

            installPhase = ''
              runHook preInstall

              mkdir -p $out/bin
              mkdir -p $out/lib/textbridge
              mkdir -p $out/share/systemd/user
              mkdir -p $out/share/doc/textbridge

              cp textbridge_server.py $out/lib/textbridge/textbridge_server.py
              cp config.example.json $out/share/doc/textbridge/config.example.json
              cp textbridge-server.service $out/share/systemd/user/textbridge-server.service

              patchShebangs $out/lib/textbridge/textbridge_server.py
              chmod +x $out/lib/textbridge/textbridge_server.py
              ln -s $out/lib/textbridge/textbridge_server.py $out/bin/textbridge-server
              substituteInPlace $out/share/systemd/user/textbridge-server.service \
                --replace-fail "%h/.local/lib/textbridge/textbridge_server.py" \
                "$out/bin/textbridge-server"

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
      });

      nixosModules =
        let
          serverModule = { config, lib, pkgs, ... }:
          let
            cfg = config.services.textbridge.server;
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

            config = lib.mkIf cfg.enable {
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

              environment.systemPackages = [ cfg.package ] ++ lib.optional cfg.adbHelper.enable cfg.adbHelper.package;

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
            };
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

          default = pkgs.mkShell {
            packages = [
              pkgs.python3
              pkgs.cmake
              pkgs.ninja
              pkgs.pkg-config
              pkgs.fcitx5
              pkgs.ripgrep
            ];

            shellHook = ''
              echo "TextBridge dev shell"
              echo "  Server:   python3 textbridge/desktop/server/textbridge_server.py --init-config"
              echo "  Fcitx5:   cmake -S textbridge/desktop/fcitx5-addon -B build/fcitx5-addon -GNinja"
              echo "  Android:  use spreadconfig's Android template/dev environment"
            '';
          };
        });
    };
}
