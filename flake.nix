{
  description = "TextBridge Wi-Fi MVP development environment";

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
            android_sdk.accept_license = true;
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
      });

      nixosModules = {
        textbridge = { config, lib, pkgs, ... }:
          let
            cfg = config.services.textbridge;
            system = pkgs.stdenv.hostPlatform.system;
          in
          {
            options.services.textbridge = {
              enable = lib.mkEnableOption "TextBridge Wi-Fi desktop receiver";

              enableServer = lib.mkOption {
                type = lib.types.bool;
                default = true;
                description = "Whether to install and enable the TextBridge user service.";
              };

              configPath = lib.mkOption {
                type = lib.types.str;
                default = "%h/.config/textbridge/server.json";
                description = "Path to the per-user TextBridge server configuration.";
              };

              serverPackage = lib.mkOption {
                type = lib.types.package;
                default = self.packages.${system}.textbridge-server;
                defaultText = lib.literalExpression "inputs.textbridge.packages.\${pkgs.system}.textbridge-server";
                description = "Package providing the TextBridge HTTP server.";
              };

              fcitx5AddonPackage = lib.mkOption {
                type = lib.types.package;
                default = self.packages.${system}.fcitx5-textbridge;
                defaultText = lib.literalExpression "inputs.textbridge.packages.\${pkgs.system}.fcitx5-textbridge";
                description = "Package providing the Fcitx5 TextBridge addon.";
              };
            };

            config = lib.mkIf cfg.enable {
              environment.systemPackages = [ cfg.serverPackage ];
              i18n.inputMethod.fcitx5.addons = [ cfg.fcitx5AddonPackage ];

              systemd.user.services.textbridge-server = lib.mkIf cfg.enableServer {
                description = "TextBridge Wi-Fi server";
                after = [ "network-online.target" ];
                wantedBy = [ "default.target" ];
                serviceConfig = {
                  Type = "simple";
                  ExecStart = "${cfg.serverPackage}/bin/textbridge-server --config ${cfg.configPath}";
                  Restart = "on-failure";
                  RestartSec = 2;
                };
              };
            };
          };

        default = self.nixosModules.textbridge;
      };

      devShells = forAllSystems (system:
        let
          pkgs = pkgsFor system;
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" ];
            buildToolsVersions = [ "35.0.0" "34.0.0" ];
            includeEmulator = false;
            includeSystemImages = false;
          };
          androidSdk = androidComposition.androidsdk;
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
              pkgs.gradle
              pkgs.jdk17
              pkgs.android-tools
              androidSdk
            ];

            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
            JAVA_HOME = pkgs.jdk17.home;

            shellHook = ''
              export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"
              echo "TextBridge dev shell"
              echo "  Android:  cd textbridge/android && gradle assembleDebug"
              echo "  Server:   python3 textbridge/desktop/server/textbridge_server.py --init-config"
              echo "  Fcitx5:   cmake -S textbridge/desktop/fcitx5-addon -B build/fcitx5-addon -GNinja"
            '';
          };
        });
    };
}
