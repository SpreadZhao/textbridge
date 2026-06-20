# TextBridge Wi-Fi MVP

TextBridge 把 Android 手机上输入的最终文本，经局域网 HTTP POST 转发到 Linux 桌面，并由 Fcitx5 插件调用 `InputContext::commitString()` 提交到当前获得焦点的输入框。Rime 和雾凇拼音不需要修改。

## 工程结构

```text
textbridge/
├── android/                 # Kotlin + XML Android App
├── desktop/
│   ├── server/              # Python 标准库 HTTP 服务
│   └── fcitx5-addon/        # C++ Fcitx5 Module Addon
└── tools/
    └── send_test.py         # 直接向插件 Unix socket 发测试文本
```

## NixOS 开发环境

在仓库根目录进入开发 shell：

```sh
nix develop path:.
```

该 shell 提供 Python 3、CMake、Ninja、pkg-config、Fcitx5 开发头文件、JDK、Gradle、Android SDK platform/build-tools 和 adb。

也可以按范围进入更轻量的 shell：

```sh
nix develop path:.#server
nix develop path:.#desktop
```

项目级验证：

```sh
nix flake check path:.
```

## 阶段 A：Fcitx 本地闭环

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

## 阶段 B：桌面 HTTP 服务

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

请把 `listen_host` 改成电脑实际局域网地址，不建议监听 `0.0.0.0`。启动服务：

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

## systemd 用户服务

Nix 打包：

```sh
nix build path:.#textbridge-server
```

打包结果提供 `bin/textbridge-server` 和已替换为 Nix store 路径的 systemd user unit。也可以手动复制源码版本：

```sh
install -Dm755 textbridge/desktop/server/textbridge_server.py \
  ~/.local/lib/textbridge/textbridge_server.py
install -Dm644 textbridge/desktop/server/textbridge-server.service \
  ~/.config/systemd/user/textbridge-server.service
systemctl --user daemon-reload
systemctl --user enable --now textbridge-server.service
```

查看日志：

```sh
journalctl --user -u textbridge-server -f
```

也可以在 NixOS flake 配置中直接启用模块：

```nix
{
  inputs.textbridge.url = "path:/home/spreadzhao/workspaces/rime-android-remote";

  outputs = { self, nixpkgs, textbridge, ... }: {
    nixosConfigurations.my-host = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [
        textbridge.nixosModules.textbridge
        {
          services.textbridge.enable = true;
        }
      ];
    };
  };
}
```

模块会把 Fcitx5 插件加入 `i18n.inputMethod.fcitx5.addons`，并安装用户级 `textbridge-server.service`。首次启用前仍需要为当前用户生成配置：

```sh
textbridge-server --init-config --listen-host 192.168.1.20
```

## 阶段 C：Android App

构建 debug APK：

```sh
cd textbridge/android
gradle assembleDebug
```

安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

App 单屏包含电脑地址、访问令牌、多行文本框和发送按钮。地址和令牌保存在 `SharedPreferences`；只有服务端返回 `status=ok` 时才清空正文，失败会保留正文。

## 安全边界

MVP 使用局域网明文 HTTP，仅适合本人控制的家庭或开发网络。不要在公共 Wi-Fi、校园访客网络、酒店网络或不可信局域网中发送敏感内容。

最低要求：

- 使用初始化命令生成的随机令牌；
- 服务绑定明确的局域网 IP；
- 防火墙只允许本地子网访问 `17321/tcp`；
- 不记录正文和令牌；
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
