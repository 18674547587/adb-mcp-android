# ADB MCP（Android APK）

**一台安卓通过 OTG 控制另一台安卓，并把能力全部暴露成 MCP。**

装上本 APK 的手机 = 主机（A），USB 接上的手机 = 被控端（B）。A 上的 MCP 服务监听本地端口，任何 MCP 客户端（同机 App、电脑、AI）接上去就能操作 B。

## 架构

```
[A 手机]  ADB MCP APK
   ├── UsbTransport        Android USB Host API（bulkTransfer）
   ├── AdbConnection       ADB 协议：CNXN / AUTH / OPEN / OKAY / WRTE / CLSE
   ├── AdbCrypto           RSA-2048 密钥（持久化，设备只授权一次）
   ├── McpServer           HTTP JSON-RPC，127.0.0.1:7788（可切 0.0.0.0）
   └── McpTools            23 个工具
          │ USB OTG
          ▼
[B 手机]  adbd（需开启 USB 调试）
```

不需要 root，不需要装 adb 二进制，不依赖甲壳虫。普通 App 能拿到的 USB Host 权限就是它的全部权限来源。

## 使用

1. B 打开「开发者选项 → USB 调试」
2. OTG 线连 A 和 B
3. 打开 ADB MCP → 「① 连接 USB 设备」→ 允许 USB 权限
4. B 上弹出「允许 USB 调试」→ 勾选「一律允许」并确定（密钥已持久化，只需一次）
5. 状态显示「已连接」即成功，MCP 地址 `http://127.0.0.1:7788/mcp`

需要跨设备访问时，勾选「允许局域网访问 MCP」，改用 `http://<A的IP>:7788/mcp`。

## MCP 客户端配置

```json
{
  "mcpServers": {
    "adb": {
      "type": "http",
      "url": "http://127.0.0.1:7788/mcp"
    }
  }
}
```

探活：`GET /health` 返回 `{ok, status, device, tools}`。

## 工具（23 个）

| 组 | 工具 |
|---|---|
| 状态 | `adb_status` `adb_device_info` `adb_props` `adb_screen_size` |
| Shell | `adb_shell` |
| 应用 | `adb_list_packages` `adb_package_info` `adb_install` `adb_uninstall` `adb_start_app` `adb_stop_app` `adb_clear_data` |
| 文件 | `adb_ls` `adb_pull` `adb_push` |
| 屏幕 | `adb_screenshot` `adb_tap` `adb_swipe` `adb_text` `adb_key` |
| 日志 | `adb_logcat` `adb_dumpsys` `adb_reboot` |

大文件走 base64（`adb_install` / `adb_push` / `adb_pull`），单次上限 8 MB。

## 构建

GitHub Actions 自动构建（`.github/workflows/build.yml`），推 main 即出 APK。
本地：`gradle assembleDebug`（AGP 8.5.2 / Gradle 8.7 / JDK 17）。

## 限制

- B 必须能开 USB 调试；OTG 需 A 支持 USB Host
- 权限层级 = ADB shell（uid 2000），不能 root、不能读 `/data/data` 私有目录
- 不支持 fastboot（USB 接口不同，需要单独的协议实现）
- 同一时刻只连一台设备
