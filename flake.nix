{
  description = "PCAPdroid Android traffic capture environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
      pkgsFor = system: import nixpkgs { inherit system; };
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = pkgsFor system;
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              android-tools
              curl
              jq
              wireshark-cli
              coreutils
              findutils
            ];

            shellHook = ''
              export PATH="$PWD/scripts:$PATH"
              echo "=== Android Network Traffic Capture Environment ==="
              echo "Tools available:"
              echo "  - adb (Android Debug Bridge)"
              echo "  - tshark & capinfos (Wireshark CLI)"
              echo "  - curl, jq"
              echo ""
              echo "Quick commands:"
              echo "  ./scripts/pcapdroid-install.sh      - Download & install PCAPdroid APK via ADB"
              echo "  ./scripts/pcapdroid-record.sh [min] - Run traffic capture session (default: 30 min)"
              echo "====================================================="
            '';
          };
        }
      );

      apps = forAllSystems (
        system:
        let
          pkgs = pkgsFor system;
        in
        {
          install = {
            type = "app";
            program = "${pkgs.writeShellScriptBin "pcapdroid-install" ''
              export PATH="${
                pkgs.lib.makeBinPath (
                  with pkgs;
                  [
                    android-tools
                    curl
                    jq
                    coreutils
                  ]
                )
              }:$PATH"
              exec ${./scripts/pcapdroid-install.sh} "$@"
            ''}/bin/pcapdroid-install";
          };

          record = {
            type = "app";
            program = "${pkgs.writeShellScriptBin "pcapdroid-record" ''
              export PATH="${
                pkgs.lib.makeBinPath (
                  with pkgs;
                  [
                    android-tools
                    wireshark-cli
                    coreutils
                    findutils
                    curl
                    jq
                  ]
                )
              }:$PATH"
              exec ${./scripts/pcapdroid-record.sh} "$@"
            ''}/bin/pcapdroid-record";
          };

          default = self.apps.${system}.record;
        }
      );
    };
}
