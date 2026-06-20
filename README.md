# TextBridge Wi-Fi MVP

本仓库按 `textbridge_wifi_mvp_plan.md` 初始化为多个独立工程：

- `textbridge/android`：Android Kotlin 单屏发送端。
- `textbridge/desktop/server`：Python 标准库 HTTP 到 Unix datagram 转发服务。
- `textbridge/desktop/fcitx5-addon`：Fcitx5 C++ 插件，负责向当前输入上下文提交文本。
- `textbridge/tools`：本地 Unix socket 调试工具。

NixOS 开发环境：

```sh
nix develop path:.
```

项目级验证：

```sh
nix flake check path:.
```

详细构建、安装和调试步骤见 [textbridge/README.md](textbridge/README.md)。
