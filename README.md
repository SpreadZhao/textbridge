# TextBridge

本仓库包含 TextBridge 的 Android 发送端、Linux 桌面接收端和 Fcitx5 插件：

- `textbridge/android`：Android Kotlin + Compose 发送端。
- `textbridge/desktop/server`：Python 标准库 HTTP 到 Unix datagram 转发服务。
- `textbridge/desktop/fcitx5-addon`：Fcitx5 C++ 插件，负责向当前输入上下文提交文本。
- `textbridge/tools`：本地 Unix socket 调试工具和 USB/ADB 连接辅助脚本。

桌面产物开发环境：

```sh
nix develop path:.
```

这个 flake 面向 NixOS 部署产物：Python server、Fcitx5 插件、ADB helper 和 NixOS module。Android App 是客户端源码，不作为 NixOS 产物输出；Android 开发环境使用 spreadconfig 的 Android flake template 或外部 Android Studio SDK。调试 Android 环境时运行：

```sh
scripts/android-doctor
```

项目级验证：

```sh
nix flake check path:.
```

详细构建、安装和调试步骤见 [textbridge/README.md](textbridge/README.md)。
