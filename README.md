# socksdroid — script-controllable fork for Android emulators

[English](#english) | [中文](#中文)

---

<a name="english"></a>
## English

This is a fork of [bndeff/socksdroid](https://github.com/bndeff/socksdroid) (itself an updated fork of [PeterCxy/SocksDroid](https://github.com/PeterCxy/SocksDroid)) — a SOCKS5 client for Android 5.0+ built on `VpnService`. The upstream project is in maintenance mode.

This fork keeps the original app and UI fully intact, and makes it **connect reliably on boot** and **controllable from the shell (adb)** — developed and tested on LDPlayer 9 (Android 9) on Windows.

### What was fixed

- **Boot autostart now works reliably** — the VPN connects by itself after the emulator restarts (several upstream defects that silently broke auto-connect were fixed).
- **Switching proxies while connected** — no need to disconnect first; setting a new SOCKS5 server reconnects seamlessly.
- **More robust recovery** — leftover helper processes from a previous session are cleaned up automatically, so reconnecting after an app update or an unexpected kill just works.
- **Cleaner failure handling** — a failed connection attempt no longer leaves a service that looks connected but carries no traffic.

### What was added

- **Full shell (adb) control** — set the SOCKS5 proxy, connect, disconnect and query status from the command line; no UI automation needed. Only adb/root can use this interface; other apps cannot.
- **One configuration everywhere** — a proxy set from the shell is also used by boot autostart and the app UI.
- **Two LDPlayer helper scripts** (repo root) for one-shot setup and daily control — see below.

### Helper scripts (Git Bash on Windows)

Both scripts talk to LDPlayer via `ldconsole`/`adb`. Edit the path constants (`LDCONSOLE`, `ADB`) and `EMULATOR_PROFILES` at the top if your setup differs. The setup script expects the built APK (`socksdroid*.apk`) in the current directory.

**`setup_socksdroid_ldplayer.sh`** — one-shot install & configure per instance: install the APK, grant VPN permission, set the proxy, connect, enable system always-on VPN, and finally reboot the instance to verify the VPN comes up by itself.

**`control_socksdroid_ldplayer.sh`** — daily control (instance must be running):

```bash
bash control_socksdroid_ldplayer.sh <instance> on      # connect with the saved proxy
bash control_socksdroid_ldplayer.sh <instance> set "socks5://[u:p@]ip:port"   # set proxy + connect
bash control_socksdroid_ldplayer.sh <instance> off     # disconnect (also clears always-on)
bash control_socksdroid_ldplayer.sh <instance> status  # show proxy / tun0 / always-on state
```

### Build

```bash
# JDK 17 + Android SDK (platforms;android-31, build-tools;31.0.0)
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

### License

GPL-3.0, inherited from upstream.

---

<a name="中文"></a>
## 中文

本仓库 fork 自 [bndeff/socksdroid](https://github.com/bndeff/socksdroid)(它又是 [PeterCxy/SocksDroid](https://github.com/PeterCxy/SocksDroid) 的维护版)—— 一个基于 `VpnService` 的 Android 5.0+ SOCKS5 客户端。上游已进入维护模式。

本 fork **完整保留原应用与 UI**,使其**开机自动连接可靠**、并**支持 shell(adb)命令控制** —— 在 Windows 的雷电模拟器 LDPlayer 9(Android 9)上开发与测试。

### 修复了什么

- **开机自启可靠** —— 模拟器重启后 VPN 自动连接,不再时灵时不灵(修复了上游多个导致自动连接失效的缺陷)。
- **连接中可直接换代理** —— 无需先断开,设置新的 SOCKS5 服务器即自动无缝重连。
- **异常后恢复更稳** —— 上次会话残留的辅助进程会被自动清理,应用升级或被强杀后重连即恢复正常。
- **失败不再"假连接"** —— 连接失败后不会留下一个看似在运行、实际没有流量的服务。

### 新增了什么

- **完整的 shell(adb)命令控制** —— 命令行即可设置 SOCKS5 代理、连接、断开、查询状态,无需任何 UI 自动化。该接口仅 adb/root 可用,其他应用无法调用。
- **配置一处生效** —— 命令行设置的代理,开机自启和 App 界面同样使用。
- **附带两个雷电辅助脚本**(仓库根目录):一键部署 + 日常控制,见下文。

### 辅助脚本(Windows Git Bash)

两个脚本通过 `ldconsole`/`adb` 操作雷电模拟器。路径常量(`LDCONSOLE`、`ADB`)与 `EMULATOR_PROFILES` 按需修改。setup 脚本要求构建产物(`socksdroid*.apk`)位于当前目录。

**`setup_socksdroid_ldplayer.sh`** —— 每实例一键部署:安装 APK、授权 VPN、设置代理、连接、开启系统级 always-on,最后自动重启实例验证 VPN 能自行连接。

**`control_socksdroid_ldplayer.sh`** —— 日常控制(实例须处于运行状态):

```bash
bash control_socksdroid_ldplayer.sh <实例名> on       # 用已保存的代理连接
bash control_socksdroid_ldplayer.sh <实例名> set "socks5://[u:p@]ip:port"   # 设置代理并连接
bash control_socksdroid_ldplayer.sh <实例名> off      # 断开(同时清除 always-on)
bash control_socksdroid_ldplayer.sh <实例名> status   # 查看代理 / tun0 / always-on
```

### 构建

```bash
# 需要 JDK 17 + Android SDK(platforms;android-31,build-tools;31.0.0)
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

### 许可

GPL-3.0(继承自上游)。
