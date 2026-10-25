package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"tailscale.com/tsnet"
)

// tsManager 内嵌 Tailscale 组网：让电脑直接加入用户自己的 Tailscale 网络，
// 手机装 Tailscale APP 登录同一账号后，即可在任意网络访问电脑（100.x.x.x）。
// 电脑端不需要单独安装 Tailscale 客户端。
type tsManager struct {
	mu        sync.Mutex
	started   bool
	loginURL  string
	lastErr   string
	ip        string
	hostname  string
	stateDir  string
	srv       *tsnet.Server
	ln        net.Listener
	handler   http.Handler
	triggered chan struct{}
}

var loginURLRe = regexp.MustCompile(`https://login\.tailscale\.com/a/[A-Za-z0-9]+`)

func newTSManager(stateDir string) *tsManager {
	return &tsManager{
		stateDir:  stateDir,
		triggered: make(chan struct{}, 1),
	}
}

// ensureStarted 启动组网（幂等）。未登录时 tsnet 会通过日志输出登录链接。
// handler 为 HTTP 服务入口：同时挂载到 Tailscale 组网网卡（100.x.x.x:8123）
// 与物理网卡（局域网 :8123），两套网络共用同一个服务。
func (t *tsManager) ensureStarted(handler http.Handler) {
	if handler != nil {
		t.mu.Lock()
		t.handler = handler
		t.mu.Unlock()
	}
	t.mu.Lock()
	if t.started {
		t.mu.Unlock()
		return
	}
	if t.handler == nil {
		t.mu.Unlock()
		log.Printf("[tailscale] handler 未就绪，暂不启动组网")
		return
	}
	t.started = true
	t.mu.Unlock()

	stateDir := t.stateDir
	srv := &tsnet.Server{
		Hostname: "filebridge",
		Dir:      stateDir,
		UserLogf: func(format string, args ...any) {
			msg := fmt.Sprintf(format, args...)
			log.Printf("[tailscale] %s", msg)
			if m := loginURLRe.FindString(msg); m != "" {
				t.mu.Lock()
				t.loginURL = m
				t.mu.Unlock()
				log.Printf("[tailscale] 登录链接已捕获，请在配置页完成授权")
			}
		},
	}
	t.srv = srv

	// 后台 Listen：等待认证期间会阻塞，放 goroutine；拿到监听后挂载 HTTP 服务
	go func() {
		ln, err := srv.Listen("tcp", ":8123")
		if err != nil {
			t.mu.Lock()
			t.lastErr = err.Error()
			t.mu.Unlock()
			log.Printf("[tailscale] 组网监听失败: %v", err)
			return
		}
		t.mu.Lock()
		t.ln = ln
		handler := t.handler
		t.mu.Unlock()
		log.Printf("[tailscale] 组网监听已就绪 :8123（http://100.x.x.x:8123）")
		// 关键：把 HTTP 服务挂到 Tailscale 网卡，手机走组网地址即可访问
		if err := http.Serve(ln, handler); err != nil {
			log.Printf("[tailscale] 组网 HTTP 服务退出: %v", err)
		}
	}()

	// 周期性上报状态（IP 在认证完成后出现）
	go func() {
		for {
			time.Sleep(3 * time.Second)
			if err := t.refreshStatus(); err == nil {
				t.mu.Lock()
				ip := t.ip
				login := t.loginURL
				t.mu.Unlock()
				if ip != "" {
					log.Printf("[tailscale] 组网地址: http://%s:8123", ip)
					if login != "" {
						// 已登录成功后清掉遗留登录链接
						t.mu.Lock()
						t.loginURL = ""
						t.mu.Unlock()
					}
					return
				}
			}
		}
	}()
}

// refreshStatus 从 LocalClient 查询本机组网 IP 与主机名
func (t *tsManager) refreshStatus() error {
	t.mu.Lock()
	srv := t.srv
	t.mu.Unlock()
	if srv == nil {
		return fmt.Errorf("not started")
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil || st == nil {
		return fmt.Errorf("status: %v", err)
	}
	if st.Self == nil {
		return fmt.Errorf("no self")
	}
	var ip string
	for _, a := range st.Self.TailscaleIPs {
		if a.Is4() {
			ip = a.String()
			break
		}
	}
	t.mu.Lock()
	t.ip = ip
	t.hostname = st.Self.HostName
	t.mu.Unlock()
	if ip == "" {
		return fmt.Errorf("no ip yet")
	}
	return nil
}

// triggerLogin 触发/确认登录流程，返回登录链接（如果有）
func (t *tsManager) triggerLogin() string {
	go t.ensureStarted(nil)
	time.Sleep(500 * time.Millisecond)
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.ip != "" {
		return "" // 已登录
	}
	if t.loginURL != "" {
		return t.loginURL
	}
	return ""
}

// status 当前组网状态（供配置页展示）
func (t *tsManager) status() map[string]any {
	_ = t.refreshStatus()
	t.mu.Lock()
	defer t.mu.Unlock()
	res := map[string]any{
		"loggedIn": t.ip != "",
		"ip":       t.ip,
		"hostname": t.hostname,
		"loginUrl": t.loginURL,
	}
	if t.lastErr != "" {
		res["error"] = t.lastErr
	}
	return res
}

// isTailscaleListener 是否有组网监听（备用，暂未使用）
func (t *tsManager) isTailscaleListener() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.ln != nil
}

var _ = strings.TrimSpace
