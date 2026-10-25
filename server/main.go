package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
)

// FileBridge 电脑端服务
// 功能：HTTP 文件服务（列目录/下载/上传，支持 Range 断点续传）+ Basic/token 鉴权
//       + 本机 Web 配置页 + Windows 开机自启 + 内嵌 Tailscale 组网（tsnet）
// 电脑端配置一次，之后开机自动拉起、自动连接；手机 APP 与它配套使用。

// version 服务版本号（配置页会显示，便于确认电脑端已更新）
const version = "4.2"

var (
	configPath   = "filebridge-config.json" // 最终按 dataDir 解析
	stateDirName = "tsnet-state"
	listenPort   = 8123
)

func main() {
	daemon := flag.Bool("daemon", false, "以守护模式运行（隐藏控制台窗口）")
	install := flag.Bool("install", false, "注册开机自启计划任务并退出")
	uninstall := flag.Bool("uninstall", false, "移除开机自启计划任务并退出")
	portFlag := flag.Int("port", 0, "HTTP 监听端口（默认 8123）")
	flag.Parse()

	if *portFlag != 0 {
		listenPort = *portFlag
	}
	if *install {
		if err := installAutostart(); err != nil {
			log.Fatalf("注册开机自启失败: %v", err)
		}
		log.Println("开机自启已注册（计划任务 FileBridgeServer）")
		return
	}
	if *uninstall {
		if err := removeAutostart(); err != nil {
			log.Fatalf("移除开机自启失败: %v", err)
		}
		log.Println("开机自启已移除")
		return
	}

	// 配置与组网状态统一放到用户目录 %USERPROFILE%\.filebridge（永远可写）
	migrateLegacyData()
	configPath = filepath.Join(dataDir(), "filebridge-config.json")
	stateDirName = filepath.Join(dataDir(), "tsnet-state")
	_ = os.MkdirAll(dataDir(), 0o700)

	// 运行时目录：程序所在目录（Windows 上 exe 旁，便于用户整体拷贝）
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		if dir != "" && dir != "." {
			_ = os.Chdir(dir)
		}
	}

	if *daemon {
		hideConsole()
	}

	cfg, err := loadConfig(configPath)
	if err != nil {
		log.Printf("未找到配置，将以首启向导模式启动: %v", err)
		cfg = &Config{}
		cfg.filename = configPath
	}

	ts := newTSManager(stateDirName)

	api := &apiServer{cfg: cfg, ts: ts}

	mux := http.NewServeMux()
	mux.HandleFunc("/", api.handleRoot)               // 本机配置页
	mux.HandleFunc("/api/list", api.handleList)       // 列目录（鉴权）
	mux.HandleFunc("/api/download", api.handleDownload) // 下载（鉴权，Range）
	mux.HandleFunc("/api/thumb", api.handleThumb)       // 缩略图（鉴权）
	mux.HandleFunc("/api/upload", api.handleUpload)   // 上传（鉴权）
	mux.HandleFunc("/api/config", api.handleConfig)   // 保存配置（仅本机）
	mux.HandleFunc("/api/autostart", api.handleAutostart) // 自启开关（仅本机）
	mux.HandleFunc("/api/tailscale/status", api.handleTSStatus) // 组网状态
	mux.HandleFunc("/api/tailscale/login", api.handleTSLogin)   // 触发组网登录

	addr := "0.0.0.0:" + strconv.Itoa(listenPort)
	log.Printf("FileBridge 服务已启动，监听 %s", addr)
	log.Printf("本机配置页: http://127.0.0.1:%d/", listenPort)
	log.Printf("共享目录: %q（未配置时手机端无法访问）", cfg.RootDir)

	// 后台自动启动 Tailscale 组网（未登录时在配置页给出登录链接）
	// 同一 HTTP 服务同时挂载到物理网卡（局域网）与 Tailscale 网卡（组网）
	go ts.ensureStarted(logRequests(mux))

	if err := http.ListenAndServe(addr, logRequests(mux)); err != nil {
		log.Fatalf("HTTP 服务异常退出: %v", err)
	}
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		next.ServeHTTP(w, r)
		if r.URL.Path == "/api/download" || r.URL.Path == "/api/list" {
			// 请求日志精简，避免刷屏
		}
	})
}
