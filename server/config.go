package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// dataDir 配置/组网状态目录：固定放在用户目录（永远可写，不受 exe 所在目录权限影响）
// Windows: %USERPROFILE%\.filebridge    Linux: ~/.filebridge
func dataDir() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = "."
	}
	return filepath.Join(home, ".filebridge")
}

// migrateLegacyData 把旧版（exe 旁）的配置与组网状态迁移到用户目录，避免用户重新设置
func migrateLegacyData() {
	exeDir := "."
	if exe, err := os.Executable(); err == nil {
		exeDir = filepath.Dir(exe)
	}
	dir := dataDir()
	_ = os.MkdirAll(dir, 0o700)

	// 配置文件迁移
	legacyCfg := filepath.Join(exeDir, "filebridge-config.json")
	newCfg := filepath.Join(dir, "filebridge-config.json")
	if _, err := os.Stat(newCfg); err != nil && os.IsNotExist(err) {
		if data, err := os.ReadFile(legacyCfg); err == nil {
			if err := os.WriteFile(newCfg, data, 0o600); err == nil {
				log.Printf("已迁移旧配置到 %s", newCfg)
			}
		}
	}

	// 组网状态迁移（保留已完成的 Tailscale 授权，无需重新登录）
	legacyState := filepath.Join(exeDir, "tsnet-state")
	newState := filepath.Join(dir, "tsnet-state")
	if _, err := os.Stat(newState); err != nil && os.IsNotExist(err) {
		if info, err := os.Stat(legacyState); err == nil && info.IsDir() {
			if err := copyDir(legacyState, newState); err == nil {
				log.Printf("已迁移组网状态到 %s（无需重新授权）", newState)
			}
		}
	}
}

func copyDir(src, dst string) error {
	entries, err := os.ReadDir(src)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dst, 0o700); err != nil {
		return err
	}
	for _, e := range entries {
		s := filepath.Join(src, e.Name())
		d := filepath.Join(dst, e.Name())
		if e.IsDir() {
			if err := copyDir(s, d); err != nil {
				return err
			}
			continue
		}
		in, err := os.Open(s)
		if err != nil {
			return err
		}
		out, err := os.Create(d)
		if err != nil {
			in.Close()
			return err
		}
		_, err = io.Copy(out, in)
		in.Close()
		out.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

// Config 服务配置
type Config struct {
	RootDir  string   `json:"rootDir"`  // 共享根目录（兼容保留，等价于 Dirs[0]）
	Dirs     []string `json:"dirs"`     // 共享目录列表（每行一个，支持多个）
	Username string   `json:"username"` // 访问用户名
	Password string   `json:"password"` // 访问密码（明文存储在本机配置，可接受；手机端输入同样凭据）

	filename string // 配置文件路径（不序列化）
}

// roots 返回当前共享目录列表（多目录；兼容只有 RootDir 的旧配置）
func (c *Config) roots() []string {
	if len(c.Dirs) > 0 {
		return c.Dirs
	}
	if c.RootDir != "" {
		return []string{c.RootDir}
	}
	return nil
}

// loadConfig 读取并校验配置文件
func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return nil, err
	}
	c.filename = path
	// 旧配置只有 RootDir → 迁移成 Dirs
	if len(c.Dirs) == 0 && c.RootDir != "" {
		c.Dirs = []string{c.RootDir}
	}
	for i, d := range c.Dirs {
		c.Dirs[i] = normalizePath(d)
	}
	if len(c.Dirs) > 0 {
		c.RootDir = c.Dirs[0]
	}
	return &c, nil
}

// saveConfig 持久化配置
func (c *Config) saveConfig() error {
	if c.filename == "" {
		c.filename = configPath
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(c.filename, data, 0o600)
}

// completed 配置是否已完成（目录与凭据都已设置）
func (c *Config) completed() bool {
	return len(c.roots()) > 0 && c.Username != "" && c.Password != ""
}

// validateCredential 校验用户名密码
func (c *Config) validateCredential(user, pass string) bool {
	if c.Username == "" || c.Password == "" {
		return false
	}
	return user == c.Username && pass == c.Password
}

// checkToken 校验 Basic 头或 ?token= 参数
// token = base64(username:password)，与手机端一致
func (c *Config) checkToken(r *http.Request) bool {
	user, pass, ok := r.BasicAuth()
	if ok && c.validateCredential(user, pass) {
		return true
	}
	if t := r.URL.Query().Get("token"); t != "" {
		raw, err := base64.StdEncoding.DecodeString(t)
		if err != nil {
			return false
		}
		parts := strings.SplitN(string(raw), ":", 2)
		if len(parts) == 2 && c.validateCredential(parts[0], parts[1]) {
			return true
		}
	}
	return false
}

// tokenValue 供配置页显示/手机端参考的 token 值
func (c *Config) tokenValue() string {
	return base64.StdEncoding.EncodeToString([]byte(c.Username + ":" + c.Password))
}

// normalizePath 规范化共享目录路径
// - 保留 Windows 盘符根（C:\ / C:/ 保持根形式，不会被截断成相对路径 C:）
// - 去掉其它路径的尾部冗余分隔符
func normalizePath(p string) string {
	if p == "" {
		return ""
	}
	p = strings.TrimSpace(p)
	if p == "." || p == "/" {
		return p
	}
	// Windows 盘符根：形如 C:\ 或 C:/（去掉尾部斜杠后恰好是 盘符+冒号）
	if len(p) >= 3 && isAlpha(p[0]) && p[1] == ':' &&
		(p[2] == '\\' || p[2] == '/') &&
		len(strings.Trim(p[2:], `/\`)) == 0 {
		return p[:2] + string(os.PathSeparator)
	}
	// 其它路径：去掉尾部冗余分隔符（空结果时保留首字符，如 / 或 \）
	t := strings.TrimRight(p, `/\`)
	if t == "" {
		if len(p) > 0 && (p[0] == '/' || p[0] == '\\') {
			return p[:1]
		}
		return p
	}
	return t
}

func isAlpha(c byte) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}

// hashString 简单摘要（用于日志展示，不做鉴权）
func hashString(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:8])
}

var errConfigIncomplete = errors.New("服务尚未完成初始化配置，请在电脑上打开本机配置页完成设置")

func logConfigState(c *Config) {
	if !c.completed() {
		log.Printf("配置未完成（缺少共享目录或凭据），手机端访问将被拒绝")
	}
}
