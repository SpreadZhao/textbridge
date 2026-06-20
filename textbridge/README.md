# TextBridge

TextBridge 把 Android 手机上输入的最终文本，经局域网 HTTP POST 转发到 Linux 桌面，并由 Fcitx5 插件调用 `InputContext::commitString()` 提交到当前获得焦点的输入框。Rime 和雾凇拼音不需要修改。

## 工程结构

```text
textbridge/
├── android/                 # Kotlin + Jetpack Compose Android App
├── desktop/
│   ├── server/              # Python 标准库 HTTP 服务
│   └── fcitx5-addon/        # C++ Fcitx5 Module Addon
└── tools/
    ├── textbridge-adb-connect # 建立 adb reverse 端口转发
    └── send_test.py         # 直接向插件 Unix socket 发测试文本
```

## 项目开发环境

在仓库根目录进入开发 shell：

```sh
nix develop path:.
```

默认 shell 覆盖整个项目开发，提供 Python 3、CMake、Ninja、pkg-config、Fcitx5 开发头文件、JDK、Gradle、Android 工具链、adb、`android-cli` 和项目 Android skills。TextBridge 的产物 outputs 仍然只负责 Python server、Fcitx5 插件、ADB helper 和 NixOS module。

Android skills 通过 devShell 内部的固定源码依赖安装，不作为 flake input 暴露给下游。这样 NixOS 配置引用 `inputs.textbridge` 时不会因为开发工具链把 Android skills 写进自己的 `flake.lock`。

Android SDK 选择规则：

- 如果外部 Android Studio SDK 包含 `platforms/android-37.0` 和 `build-tools/37.0.0`，优先使用外部 SDK；
- 否则回退到 flake 内的 Nix Android SDK，保证当前项目仍可构建；
- 调试 Android 环境时运行 `scripts/android-doctor` 查看当前 SDK、adb、设备和 Gradle 状态。

也可以按范围进入更轻量的 shell：

```sh
nix develop path:.#server
nix develop path:.#desktop
nix develop path:.#android
```

项目级验证：

```sh
nix flake check path:.
```

## Fcitx 本地闭环

构建插件：

```sh
nix build path:.#fcitx5-textbridge
```

或在开发 shell 中直接用 CMake：

```sh
cmake -S textbridge/desktop/fcitx5-addon -B build/fcitx5-addon -GNinja
cmake --build build/fcitx5-addon
```

安装到用户 profile 或 Nix profile 后重启 Fcitx5。插件运行后会监听：

```text
$XDG_RUNTIME_DIR/textbridge/fcitx.sock
```

直接发送测试：

```sh
python3 textbridge/tools/send_test.py "中文 ASCII emoji 😀
第二行"
```

若当前没有普通输入焦点，预期返回 `no_focused_input`；若 Rime/Fcitx 正在组词，预期返回 `busy_composing`；密码或敏感输入框返回 `sensitive_field`。

## 桌面 HTTP 服务

初始化配置：

```sh
nix run path:.#textbridge-server -- \
  --init-config \
  --listen-host 192.168.1.20
```

配置文件默认写入：

```text
~/.config/textbridge/server.json
```

Discovery 默认监听 UDP `17322`，用于 Android App 的“扫描电脑”按钮发现服务。请把 `listen_host` 改成电脑实际局域网地址；如果监听 `0.0.0.0`，discovery 响应会尽量返回到达请求来源所用的本机地址。启动服务：

```sh
nix run path:.#textbridge-server
```

用 curl 验证：

```sh
curl -sS \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"id":"0cb2d5d8-1222-4fb7-bd10-6a02613dc18e","text":"来自 curl 的文本"}' \
  http://192.168.1.20:17321/v1/commit
```

服务日志只记录请求 ID、状态、HTTP 状态、来源地址和耗时，不记录正文或令牌。

运行服务端协议和本地 Unix socket 转发测试：

```sh
nix develop path:.#server -c python3 textbridge/desktop/server/test_textbridge_server.py
```

## NixOS 模块

Nix 打包：

```sh
nix build path:.#textbridge-server
```

推荐用 TextBridge 的 NixOS server module 管理 Python server、systemd 用户服务、server 配置和防火墙端口。访问令牌通过 `tokenFile` 在运行时读取，不会写进 Nix store。module 默认也会安装独立的 `textbridge-adb-connect` 包，用于 USB/ADB 模式建立端口转发：

```nix
{
  imports = [
    inputs.textbridge.nixosModules.server
  ];

  sops.secrets.textbridge-token = {
    owner = "spreadzhao";
    group = "users";
    mode = "0400";
  };

  services.textbridge.server = {
    enable = true;
    tokenFile = config.sops.secrets."textbridge-token".path;
    listenHost = "0.0.0.0";
    port = 17321;
    adbHelper.enable = true;
    discovery.port = 17322;
  };
}
```

生成令牌示例：

```sh
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

Fcitx5 插件仍按输入法配置接入：

```nix
i18n.inputMethod.fcitx5.addons = [
  inputs.textbridge.packages.${pkgs.system}.fcitx5-textbridge
];
```

查看日志：

```sh
journalctl --user -u textbridge-server -f
```

## Android App

构建 debug APK：

```sh
cd textbridge/android
gradle assembleDebug
```

安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

App 使用 Compose 和 Navigation 3，包含发送、历史、配置三个页面。配置页先选择发送方式：

- `局域网`：填写电脑地址，或用“扫描电脑”通过 UDP discovery 自动发现；发现端口需要和 `services.textbridge.server.discovery.port` 一致。
- `USB/ADB`：填写 TextBridge server 的 TCP 端口；App 会发送到手机本机的 `127.0.0.1:<port>`，由电脑侧 `adb reverse` 转发到桌面 server。

地址、发现端口、ADB 端口、发送方式、令牌和最近 50 条成功发送历史保存在 DataStore；只有服务端返回 `status=ok` 时才清空正文并写入历史，失败会保留正文且不记录历史。

## USB/ADB 模式

USB/ADB 模式适合手机和电脑不在同一局域网、或者局域网广播不可用的场景。它仍使用同一个 HTTP commit 协议和同一个 token。

1. 启动 TextBridge server，并确认端口，例如 `17321`。
2. 手机开启 USB 调试，连接电脑并授权。
3. 在电脑上建立反向端口转发：

```sh
textbridge-adb-connect --port 17321
```

或直接通过 flake 运行：

```sh
nix run path:.#textbridge-adb-connect -- --port 17321
```

多台设备同时连接时指定 serial：

```sh
textbridge-adb-connect --serial <device> --port 17321
```

4. Android 配置页选择 `USB/ADB`，服务端口填同一个端口，保存配置后发送。

查看或移除转发：

```sh
textbridge-adb-connect --list
textbridge-adb-connect --remove --port 17321
```

UDP discovery 只用于局域网模式，不会经过 `adb reverse`。

## 安全边界

当前版本使用局域网明文 HTTP，仅适合本人控制的家庭或开发网络。不要在公共 Wi-Fi、校园访客网络、酒店网络或不可信局域网中发送敏感内容。

最低要求：

- 使用随机令牌，并通过 `tokenFile` 或同等秘密管理方式提供给 server；
- 防火墙只允许本地子网访问配置的 HTTP TCP 端口和 discovery UDP 端口；
- 服务端不记录正文和令牌；Android App 会在手机本机保存最近 50 条成功发送文本历史；
- 对密码或敏感输入框尽力拒绝；
- 单次文本默认限制为 16 KiB。

## 卸载

```sh
systemctl --user disable --now textbridge-server.service
rm -f ~/.config/systemd/user/textbridge-server.service
rm -rf ~/.local/lib/textbridge
rm -rf ~/.config/textbridge
```

Fcitx5 插件的卸载方式取决于安装位置。若通过 Nix profile 安装，使用相应的 `nix profile remove`；若手动安装，删除 `lib/fcitx5/textbridge.so` 和 `share/fcitx5/addon/textbridge.conf` 后重启 Fcitx5。
