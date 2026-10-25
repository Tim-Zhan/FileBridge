# FileBridge

手机随时访问电脑文件 —— 体验像本地一样。Windows / macOS 服务端 + Android 客户端配套使用。

## 功能

- **远程浏览 / 下载 / 上传**：手机访问电脑的共享目录，任意文件下载到本地或上传回电脑
- **原生打开方式**：点开文件走手机系统"打开方式"，用你自己装的软件（WPS、Office、看图、播放器等）直接打开；支持"总是用此应用打开"记忆
- **图片 / 视频浏览**：照片左右滑动翻页、双击放大缩小并拖动，视频走系统播放器
- **外网访问（Tailscale 组网）**：电脑登录一次 Tailscale，手机在任何网络（4G/5G/WiFi）都能访问，无需公网端口、无需路由器设置
- **开机自启**：Windows 注册自启动（HKCU Run）、macOS launchd 自启动，重启自动拉起，无需重新配置
- **多共享目录**：支持配置多个文件夹（一行一个）
- **缓存自动清理**：预览缓存定时自动清除，不占手机空间
- **桌面控制台**：电脑端有独立设置窗口 + 系统托盘，无需浏览器操作

## 快速上手

### 电脑端（选你的系统）
- **Windows**：运行 `FileBridge-电脑端-安装版-v5.2.exe` 一键安装（也可用便携版 zip 解压即用）
- **macOS**：下载对应芯片的包（Apple 芯片 / Intel 芯片），解压后把 `FileBridge.app` 拖入"应用程序"

首次打开设置窗口，配置：
1. 共享目录（一行一个，例如 `D:\照片` / `/Users/name/照片`）
2. 用户名 / 密码（手机连接用）
3. 开机自启（可选）
4. 外网访问：点"登录组网"授权 Tailscale，拿组网 IP（100.120.x.x）

### 手机端（Android）
1. 安装 `FileBridge.apk`
2. 添加服务器：地址填电脑的 **Tailscale 组网 IP**（100.120.x.x）或**局域网 IP**，端口 `8123`，账号密码同上
3. 浏览目录 → 单击预览 / 长按选择"用其他应用打开""下载" → 列表底部"➕ 上传文件到此目录"可上传

> 用组网 IP 时手机需安装官方 Tailscale App 并登录**与电脑同一账号**；同一 WiFi 下直接用局域网 IP 则无需 Tailscale。

## 项目结构

```
server/     文件服务端（Go，内含 Tailscale 组网、Range 直链、鉴权、配置页）
desktop/    电脑端桌面控制台（Electron，Windows/macOS 通用壳）
installer/  Windows 安装器（自解压安装）
android/    安卓客户端（Kotlin）
```

## Release

GitHub Releases 提供：Windows 安装版 / macOS 双芯片版 / APK / 使用说明。完整使用说明见仓库内 `使用说明.txt` 与 `FileBridge-macOS-使用说明.txt`。
