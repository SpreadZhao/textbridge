# TextBridge

本仓库包含 TextBridge 的 Android 发送端、Linux 桌面接收端和 Fcitx5 插件：

- `textbridge/android`：Android Kotlin + Compose 发送端。
- `textbridge/desktop/server`：Python 标准库 HTTP 到 Unix datagram 转发服务。
- `textbridge/desktop/fcitx5-addon`：Fcitx5 C++ 插件，负责向当前输入上下文提交文本。
- `textbridge/tools`：本地 Unix socket 调试工具。

NixOS 开发环境：

```sh
nix develop path:.
```

Android 环境基于 spreadconfig 的 Android flake template 做了 TextBridge 定制：shell 会安装项目本地 Android agent skills，并优先使用可满足 API 37/build-tools 37.0.0 的 Android Studio SDK；如果外部 SDK 缺少组件，会回退到 flake 内的 Nix Android SDK。环境异常时运行：

```sh
scripts/android-doctor
```

项目级验证：

```sh
nix flake check path:.
```

详细构建、安装和调试步骤见 [textbridge/README.md](textbridge/README.md)。
