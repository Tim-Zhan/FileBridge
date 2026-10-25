# PigeonFile 信鸽

> 手机随时访问电脑文件 —— 体验像本地一样。
> Your PC's files, in your pocket — browse, preview, and transfer as if they were local.

**信鸽（PigeonFile）** 是一款**手机电脑文件互传 / 远程访问**开源工具：电脑上装一份服务端，手机上装一份客户端，之后无论在同一个 WiFi 下、还是在千里之外的 4G/5G 网络，手机都能像翻本地文件一样浏览、预览、下载、上传电脑里的文件，支持**跨目录搜索**与 **Tailscale 一键组网**（无需公网 IP、无需端口映射）。名字取自「飞鸽传书」——把电脑上的文件，像信鸽一样送到你的掌上。

**PigeonFile** is an open-source **PC ↔ Phone file transfer & remote access** tool. Install the server on your computer and the client on your phone, and from then on — whether you are on the same Wi-Fi or thousands of miles away on cellular data — you can browse, preview, download, and upload files on your PC, just as if they were on your phone. It also supports cross-folder search and one-click Tailscale networking (no public IP or port forwarding required). The name comes from the carrier pigeon — it delivers your PC's files straight to your pocket.

---

## ✨ 功能一览 / Features

| 功能 / Feature | 说明 / Description |
| --- | --- |
| 📂 远程浏览 | 像本地文件管理器一样，按目录一层层浏览电脑的共享文件夹，文件夹始终置顶排序 |
| 🔍 **跨目录搜索** | 输入文件名，**递归搜索所有共享目录**（最多 300 条结果），每条结果都标注所在路径，点文件直接打开、点文件夹直接跳进那个目录 |
| 🔀 **排序** | 按「名称」或「修改时间」排序，一键切换，文件夹永远排最前 |
| 🖼️ 相册式看图 | 照片左右滑动翻页，双击放大 / 缩小、拖动查看细节，就像刷手机相册 |
| 🎬 视频直链播放 | 视频、音频流式播放（Range 断点续传），进度条随便拖，不占用手机存储 |
| 📄 系统打开方式 | 点文件自动走手机「打开方式」：WPS、Office、看图、播放器……用你自己装的软件打开；支持「总是用此应用打开」记忆 |
| 🌐 **一键组网** | 内置官方 Tailscale：未安装时一键从内置包安装官方 App，已安装则一键跳转登录；顶部状态条实时显示「组网：已开启 / 未开启」 |
| 📤 上传 | 目录列表底部「➕ 上传文件到此目录」，从手机选文件直接传回电脑当前文件夹 |
| 🗂️ 多共享目录 | 电脑端可配置多个共享文件夹（一行一个），手机端一个服务器条目即可访问全部 |
| 🔐 账号鉴权 | 用户名 + 密码保护，任何请求都需验证，不裸奔在网络上 |
| ⚡ 开机自启 | Windows 注册表自启、macOS launchd 自启，电脑重启后服务自动拉起，无需手动操作 |
| 🖥️ 桌面控制台 | 电脑端独立设置窗口 + 系统托盘图标，开关服务、看组网状态全在一个小窗口里，不用碰浏览器 |
| 🧹 缓存自清理 | 图片预览缓存定时自动清理，绝不把手机存储占满 |

## 🚀 快速上手 / Quick Start

### 一、电脑端 / On your PC

1. **Windows**：运行 `PigeonFile-Setup-Windows-v7.0.exe`，一键安装（自动创建桌面/开始菜单快捷方式，控制面板可卸载）。
2. **macOS**：下载对应芯片的包（`PigeonFile-macOS-Apple-Silicon-v7.0.zip` 或 `PigeonFile-macOS-Intel-v7.0.zip`），解压后把 `PigeonFile.app` 拖入「应用程序」。
3. 打开设置窗口，配置：
   - **共享目录**：一行一个，例如 `D:\照片`、`E:\文档`（Windows）或 `/Users/你的名字/照片`（macOS）
   - **用户名 / 密码**：手机端连接用，务必设置
   - **开机自启**（可选，推荐开启）
4. （可选）**登录 Tailscale 组网**：在设置窗口点登录并授权，拿到组网 IP（`100.x.x.x`）——这一步让你**不在家也能访问**，无需公网 IP、无需路由器端口映射。

### 二、手机端 / On your phone

1. 安装 `PigeonFile.apk`。
2. 点右上角「**组网**」按钮：
   - 手机未装 Tailscale → 弹窗点「安装」，从内置包一键装官方 App；
   - 已安装 → 直接跳转 Tailscale，**用与电脑同一个账号登录**并开启组网。
3. 添加服务器：名称随意，地址填电脑的 **Tailscale 组网 IP（`100.x.x.x`）**或**局域网 IP**，端口 `8123`，账号密码与电脑一致。
4. 完成！浏览 → 点文件预览 → 长按「用其他应用打开 / 下载到…」→ 列表底部「➕」上传。想找文件？点右上角放大镜，跨目录搜索全电脑。

> 小贴士：同一 WiFi 下直接用局域网 IP 即可，无需 Tailscale；外出时用组网 IP。Tailscale 图标可长按收纳/隐藏，日常只留信鸽一个图标。

## ⚠️ 关于杀软报毒 / About Antivirus False Positives

Windows 版（尤其安装版 `PigeonFile-Setup-Windows-v7.0.exe`）偶尔会被 **360、腾讯管家等杀软提示"病毒/木马"**。**这是误报**，原因很明确，可自行核实：

- **没有商业代码签名证书**：个人开源项目未购买 Authenticode 签名，Windows 与杀软对"无签名的新程序"天然警惕，这是绝大多数报毒的直接原因。
- **自解压安装包结构**：安装器 = 可执行程序 + 尾部内嵌的安装数据（自解压模式），属于杀软重点"启发式"扫描的对象。
- **行为特征像"远控"**：信鸽服务端要**开机自启 + 监听 8123 端口 + 通过 Tailscale 建立 VPN 虚拟网卡 + 后台网络连接**——这一组合在启发式引擎眼里酷似"后门程序"的特征，实际它是你本人控制的文件共享服务。
- **云端查杀无信誉**：新文件样本少，360 云查杀对没见过的新程序容易先报后放行。

**怎么核实 / 处理**：

1. **源码全公开**：`server/`、`desktop/`、`installer/`、`android/` 全部源码在仓库里，可以逐行审阅；它做了什么（端口、网卡、自启、网络请求）都写得明明白白，没有隐藏行为。
2. **选择放心**：在 360 里点"添加信任 / 恢复文件"即可正常安装使用。
3. **更稳妥**：自己用 Go 工具链从源码编译服务端（`cd server && go build`），产物行为完全一致。

> 我们不会做任何"免杀/隐藏行为"处理——代码干干净净，报毒只因它"新、没签名、长得像工具"。

## 🏗️ 项目结构 / Project Structure

```
server/     文件服务端（Go）：目录 API、账号鉴权、Range 断点续传、跨目录搜索、
            缩略图、上传、Tailscale 组网、本机配置页、开机自启
desktop/    电脑端桌面控制台（Electron，Windows / macOS 通用）：一键启动服务、
            设置窗口、系统托盘、服务日志
installer/  Windows 安装器（Go 自解压程序）：一键安装、桌面/开始菜单快捷方式、
            卸载注册、自动启动服务
android/    安卓客户端（Kotlin）：服务器列表、目录浏览、跨目录搜索、排序、
            图片/视频预览、下载打开、上传、一键组网
```

## 📄 开源协议 / License

[MIT](LICENSE) — 自由使用、修改、商用、分发，保留版权声明即可。

---

**Star ⭐ the repo if PigeonFile helps you — and PRs are welcome!**
