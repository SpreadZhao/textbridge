# TextBridge Wi‑Fi MVP 实现方案

**目标环境：** Linux + Fcitx5 + Rime（雾凇拼音）+ Android  
**版本：** MVP v0.1  
**日期：** 2026-06-20

## 1. MVP 目标

在 Android 手机上输入一段 Unicode 文本，点击“发送”后，将文本提交到 Linux 电脑上当前获得焦点、且由 Fcitx5 管理的输入框。

首版只实现以下闭环：

```text
Android App
    │  Wi‑Fi / HTTP POST
    ▼
textbridge-server（Python 用户服务）
    │  Unix Domain Datagram Socket
    ▼
fcitx5-textbridge（C++ Fcitx5 插件）
    │  InputContext::commitString()
    ▼
当前输入框
```

Rime 和雾凇拼音不需要修改。手机端发送的是最终文本，Fcitx5 插件直接提交，不再经过拼音组词。

## 2. 首版明确不做的功能

为控制复杂度，MVP 不实现：

- USB；
- 自动发现电脑（mDNS/NSD）；
- WebSocket 长连接；
- 文件、图片和富文本；
- 模拟快捷键、自动按 Enter、删除已有内容；
- 后台剪贴板同步；
- 多电脑管理；
- 公网访问；
- 自动排队后在未来焦点中输入。

手机端采用原生 Android App，但首版由用户手动填写电脑 IP、端口和共享令牌。

## 3. 技术选型

| 部分 | 选型 | 原因 |
|---|---|---|
| Android | Kotlin + 单 Activity + XML Views | 依赖少，界面和网络逻辑都很小 |
| 手机到电脑 | HTTP POST + JSON | 易调试，可直接用 curl 验证，不需要维护长连接 |
| 桌面网络服务 | Python 3 标准库 | 无第三方运行时依赖，代码量小 |
| 本地 IPC | Unix Domain Datagram Socket | 保留消息边界，适合一次请求一次响应 |
| Fcitx 接入 | C++ Shared Library Addon | 可访问完整 Fcitx5 API，并在 Fcitx 主事件循环中提交文本 |
| 服务管理 | systemd user service | 登录后自动启动、日志统一进入 journal |

## 4. 仓库结构

```text
textbridge/
├── android/
│   └── app/
│       ├── src/main/java/.../MainActivity.kt
│       ├── src/main/res/layout/activity_main.xml
│       └── src/main/AndroidManifest.xml
├── desktop/
│   ├── server/
│   │   ├── textbridge_server.py
│   │   ├── config.example.json
│   │   └── textbridge-server.service
│   └── fcitx5-addon/
│       ├── CMakeLists.txt
│       ├── src/textbridge.cpp
│       └── data/textbridge.conf.in
├── tools/
│   └── send_test.py
└── README.md
```

## 5. 网络协议

### 5.1 默认配置

```json
{
  "listen_host": "192.168.1.20",
  "listen_port": 17321,
  "token": "由程序生成的至少 192 位随机令牌",
  "max_text_bytes": 16384,
  "request_timeout_ms": 2000
}
```

建议显式绑定电脑的局域网地址，而不是默认监听所有接口。IP 变化后由用户在配置中更新。

### 5.2 提交请求

```http
POST /v1/commit HTTP/1.1
Host: 192.168.1.20:17321
Authorization: Bearer <token>
Content-Type: application/json; charset=utf-8

{
  "id": "0cb2d5d8-1222-4fb7-bd10-6a02613dc18e",
  "text": "这是一段从手机发送的文字。"
}
```

约束：

- `id`：UUID，用于日志关联和后续去重；
- `text`：有效 UTF-8；
- 空字符串拒绝；
- 首版最大 16 KiB；
- 允许换行，但换行只是文本内容，不等同于模拟 Enter；
- 令牌通过 `Authorization` 请求头发送，不放在 URL 中。

### 5.3 成功响应

```json
{
  "id": "0cb2d5d8-1222-4fb7-bd10-6a02613dc18e",
  "status": "ok",
  "target_program": "org.kde.kate"
}
```

### 5.4 错误状态

| HTTP | `status` | 含义 |
|---:|---|---|
| 400 | `invalid_request` | JSON、字段或 UTF-8 无效 |
| 401 | `unauthorized` | 令牌错误 |
| 409 | `busy_composing` | 电脑端正在进行 Rime/Fcitx 组词 |
| 403 | `sensitive_field` | 输入框被标记为密码或敏感字段 |
| 413 | `text_too_large` | 文本超过限制 |
| 503 | `no_focused_input` | 当前没有可提交的 Fcitx 输入上下文 |
| 503 | `fcitx_unavailable` | 插件未运行或本地 IPC 超时 |

手机端只有收到 `status=ok` 后才清空文本框；任何失败都保留原文。

## 6. 桌面网络服务

### 6.1 职责

`textbridge-server` 只负责：

1. 监听指定的局域网 IP 和端口；
2. 校验 Bearer Token；
3. 限制请求体大小；
4. 解析 JSON；
5. 将请求转发给 Fcitx5 插件；
6. 等待插件响应并转换为 HTTP 响应；
7. 记录不包含正文和令牌的结构化日志。

网络请求解析与 Fcitx5 分离，避免网络输入直接进入输入法主进程。

### 6.2 Python 实现边界

只使用标准库：

```text
http.server
socketserver
socket
json
secrets
hmac
hashlib
uuid
logging
```

服务端使用 `ThreadingHTTPServer`。每次请求建立一个临时 Unix Datagram 客户端 socket，向插件发送一条 JSON 数据报，等待最多 2 秒，再删除临时 socket 文件。

建议运行目录：

```text
配置：~/.config/textbridge/server.json
运行时：$XDG_RUNTIME_DIR/textbridge/
插件 socket：$XDG_RUNTIME_DIR/textbridge/fcitx.sock
日志：journalctl --user -u textbridge-server
```

运行时目录权限为 `0700`，socket 文件权限为 `0600`。

### 6.3 systemd 用户服务

```ini
[Unit]
Description=TextBridge Wi-Fi server
After=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/lib/textbridge/textbridge_server.py
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
```

## 7. Fcitx5 插件

### 7.1 插件职责

`fcitx5-textbridge` 是一个极小的 Module Addon：

- 创建 `$XDG_RUNTIME_DIR/textbridge/fcitx.sock`；
- 将 socket FD 注册到 Fcitx5 事件循环；
- 接收一条本地 JSON 请求；
- 在 Fcitx 主线程中检查焦点和输入状态；
- 调用 `InputContext::commitString(text)`；
- 向发送方返回状态。

首版不在插件中实现 TCP、HTTP、TLS 或线程池。

### 7.2 核心处理逻辑

伪代码：

```cpp
Result commitText(const std::string &text) {
    if (!validUtf8(text) || text.empty() || text.size() > 16384) {
        return {"invalid_text", ""};
    }

    auto *ic = instance_->lastFocusedInputContext();
    if (!ic || !ic->hasFocus()) {
        return {"no_focused_input", ""};
    }

    if (instance_->isComposing(ic)) {
        return {"busy_composing", ic->program()};
    }

    auto flags = ic->capabilityFlags();
    if (flags.test(CapabilityFlag::Password) ||
        flags.test(CapabilityFlag::Sensitive)) {
        return {"sensitive_field", ic->program()};
    }

    ic->commitString(text);
    return {"ok", ic->program()};
}
```

密码和敏感字段判断依赖目标应用是否正确向输入法框架上报相应能力，因此只能作为尽力而为的保护。

### 7.3 本地 IPC 格式

请求：

```json
{
  "v": 1,
  "id": "UUID",
  "text": "正文"
}
```

响应：

```json
{
  "v": 1,
  "id": "UUID",
  "status": "ok",
  "target_program": "org.kde.kate"
}
```

插件应启用 `SO_PASSCRED`，并拒绝与 Fcitx 进程 UID 不一致的发送者。

## 8. Android App

### 8.1 单屏界面

```text
电脑地址：[192.168.1.20:17321]
访问令牌：[••••••••••••••••••]

┌────────────────────────────┐
│ 多行文本输入框              │
│                            │
└────────────────────────────┘

[发送]
状态：已发送 / 无焦点 / 正在组词 / 连接失败
```

地址和令牌保存在 `SharedPreferences`。令牌输入框使用密码样式，不写入日志。

### 8.2 发送流程

1. 检查地址、令牌和正文非空；
2. 禁用“发送”按钮；
3. 在后台线程中使用 `HttpURLConnection` 发起 POST；
4. 连接超时 3 秒，读取超时 5 秒；
5. 解析响应；
6. `ok` 时清空正文；
7. 失败时保留正文并显示明确原因；
8. 恢复“发送”按钮。

不得在 Android 主线程执行网络请求。

### 8.3 Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />

<application
    android:usesCleartextTraffic="true"
    ... />
```

这是为了允许 MVP 使用局域网明文 HTTP。若应用面向 Android 17 / API 37，还需要按系统要求处理局域网访问权限。正式版本应迁移到 TLS，并关闭明文流量。

## 9. 安全边界

本 MVP 的 HTTP 流量未加密，因此只允许用于本人控制的家庭或开发局域网。不要在公共 Wi‑Fi、校园访客网络、酒店网络或不可信局域网中发送敏感内容。

最低安全要求：

- 使用 `secrets.token_urlsafe(24)` 或更强的随机令牌；
- 使用常量时间比较校验令牌；
- 服务只绑定指定 LAN IP；
- 防火墙只允许本地子网访问端口 17321；
- 不记录正文和令牌；
- 密码和敏感输入框默认拒绝；
- 限制请求大小和请求超时；
- 不提供命令执行、按键模拟或任意文件访问接口。

下一安全版本再增加：自签名证书配对、证书指纹固定、HTTPS、二维码导入配置。

## 10. 实施顺序

### 阶段 A：Fcitx 本地闭环

先完成插件和 `tools/send_test.py`：

```text
send_test.py → Unix socket → Fcitx 插件 → 当前输入框
```

验收：可向文本编辑器提交中文、英文、emoji 和换行；无焦点、组词中和密码框能返回明确错误。

### 阶段 B：桌面 HTTP 服务

加入 Python 服务：

```text
curl → HTTP server → Unix socket → Fcitx 插件
```

验收：正确令牌成功，错误令牌返回 401，超长正文返回 413，插件离线返回 503。

### 阶段 C：Android App

完成单屏 App、配置持久化和错误显示。

验收：手机与电脑处于同一 Wi‑Fi 时，能稳定提交文本；失败不丢失手机端正文。

### 阶段 D：安装和启动

补齐：

- CMake 安装规则；
- Fcitx5 插件 `.conf`；
- systemd user service；
- 配置初始化命令；
- README；
- 卸载说明。

## 11. 验收标准

MVP 完成需同时满足：

1. 手机发送中文、ASCII、emoji 和多行文本均保持原样；
2. 目标为 GTK、Qt、Firefox/Chromium 中的普通输入框时至少覆盖主要桌面使用场景；
3. 当前没有输入焦点时不会把内容延迟输入到之后出现的窗口；
4. Rime 正在组词时默认拒绝，不破坏本地 preedit；
5. 发送失败时手机正文不丢失；
6. 单次请求不会重复提交；
7. 服务端日志不包含正文或令牌；
8. Fcitx 插件崩溃风险与网络解析隔离；
9. 重启 Fcitx5 或桌面服务后能够自动恢复；
10. 在 Wayland 和 X11 环境分别进行一次完整验证。

## 12. 后续升级路线

按优先级建议：

1. HTTPS + 证书固定；
2. 二维码配对和令牌导入；
3. Android NSD 自动发现；
4. 请求 ID 去重缓存；
5. “仅允许下一次发送”的桌面授权开关；
6. 多电脑配置；
7. 原生 USB 传输适配。

## 13. 参考资料

- Fcitx5 插件开发：<https://fcitx-im.org/wiki/Develop_an_simple_input_method>
- Fcitx5 `InputContext::commitString()`：<https://github.com/fcitx/fcitx5/blob/master/src/lib/fcitx/inputcontext.h>
- Fcitx5 `lastFocusedInputContext()` / `isComposing()`：<https://github.com/fcitx/fcitx5/blob/master/src/lib/fcitx/instance.h>
- Fcitx5 事件循环：<https://github.com/fcitx/fcitx5/blob/master/src/lib/fcitx-utils/eventloopinterface.h>
- Android 网络安全配置：<https://developer.android.com/privacy-and-security/security-config>
- Android 局域网服务发现，供后续版本使用：<https://developer.android.com/develop/connectivity/wifi/use-nsd>
- Android 局域网权限：<https://developer.android.com/privacy-and-security/local-network-permission>
