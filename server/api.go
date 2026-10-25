package main

import (
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/gif"
	_ "image/png"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type apiServer struct {
	cfg *Config
	ts  *tsManager
}

// ---- 鉴权 ----

func requireAuth(c *Config, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !c.completed() {
			http.Error(w, "服务尚未初始化，请在电脑浏览器打开配置页完成设置", http.StatusUnauthorized)
			return
		}
		if !c.checkToken(r) {
			w.Header().Set("WWW-Authenticate", `Basic realm="filebridge"`)
			http.Error(w, "未授权：请检查用户名/密码（或在 URL 后附加 ?token=）", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

// onlyLocal 配置管理接口只允许本机访问
func onlyLocal(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			http.Error(w, "拒绝访问", http.StatusForbidden)
			return
		}
		if host != "127.0.0.1" && host != "::1" {
			http.Error(w, "配置接口仅允许本机访问", http.StatusForbidden)
			return
		}
		next(w, r)
	}
}

// ---- 路径安全：把请求的虚拟路径映射到共享目录内 ----
// 虚拟路径格式：<共享目录序号>/<子路径>，如 "0/照片/1.jpg"、"1/文档/a.docx"
// 空路径表示"共享目录列表"（根）

// safeJoin 将请求的虚拟路径安全地映射到共享目录，返回绝对路径
func (a *apiServer) safeJoin(reqPath string) (string, error) {
	roots := a.cfg.roots()
	if len(roots) == 0 {
		return "", errConfigIncomplete
	}
	trimmed := strings.ReplaceAll(strings.Trim(reqPath, "/"), "\\", "/")
	if trimmed == "" {
		return "", nil // 根：由调用方处理为共享目录列表
	}
	segs := strings.Split(trimmed, "/")
	idx, err := strconv.Atoi(segs[0])
	if err != nil || idx < 0 || idx >= len(roots) {
		return "", fmt.Errorf("共享目录不存在或编号无效: %s", reqPath)
	}
	root := roots[idx]
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	rel := strings.Join(segs[1:], "/")
	clean := filepath.Clean(filepath.Join(absRoot, filepath.FromSlash(rel)))
	// 必须仍在所选共享目录内。
	// 用 filepath.Rel 判断（兼容共享根为盘符根目录如 C:\ 时结尾自带分隔符，
	// 旧版 HasPrefix 拼出双斜杠 C:\\ 导致所有子路径被误判越界）
	r, err := filepath.Rel(absRoot, clean)
	if err != nil || r == ".." || strings.HasPrefix(r, ".."+string(os.PathSeparator)) {
		return "", fmt.Errorf("路径越界被拒绝: %s", reqPath)
	}
	return clean, nil
}

// ---- API: 列目录 ----

type fileEntry struct {
	Name    string `json:"name"`
	IsDir   bool   `json:"isDir"`
	Size    int64  `json:"size"`
	ModTime string `json:"modTime"` // RFC3339
	Path    string `json:"path"`    // 相对根目录的路径，供后续下载
}

type listResp struct {
	Path  string      `json:"path"`
	Items []fileEntry `json:"items"`
}

func (a *apiServer) handleList(w http.ResponseWriter, r *http.Request) {
	requireAuth(a.cfg, func(w http.ResponseWriter, r *http.Request) {
		reqPath := r.URL.Query().Get("path")
		// 根：返回共享目录列表（每个目录一个"文件夹"项，点进去用 序号/ 前缀）
		roots := a.cfg.roots()
		if reqPath == "" || reqPath == "/" {
			items := make([]fileEntry, 0, len(roots))
			for i, dir := range roots {
				fi, err := os.Stat(dir)
				if err != nil {
					continue
				}
				items = append(items, fileEntry{
					Name:    filepath.Base(dir),
					IsDir:   true,
					Size:    0,
					ModTime: fi.ModTime().Format(time.RFC3339),
					Path:    strconv.Itoa(i),
				})
			}
			writeJSON(w, listResp{Path: "", Items: items})
			return
		}
		abs, err := a.safeJoin(reqPath)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		info, err := os.Stat(abs)
		if err != nil {
			if os.IsPermission(err) {
				http.Error(w, "没有权限访问该文件夹（系统目录需管理员）", http.StatusForbidden)
				return
			}
			http.Error(w, "路径不存在: "+reqPath, http.StatusNotFound)
			return
		}
		if !info.IsDir() {
			http.Error(w, "不是目录", http.StatusBadRequest)
			return
		}
		entries, err := os.ReadDir(abs)
		if err != nil {
			if os.IsPermission(err) {
				// Windows 系统目录受 NTFS 权限保护，普通权限进不去属正常
				http.Error(w, "无法访问该文件夹（没有权限，系统目录需管理员）", http.StatusForbidden)
				return
			}
			http.Error(w, "目录读取失败", http.StatusInternalServerError)
			return
		}
		items := make([]fileEntry, 0, len(entries))
		for _, e := range entries {
			fi, err := e.Info()
			if err != nil {
				continue
			}
			// 子路径统一用正斜杠拼接（不能用 filepath.Join：Windows 下会产出反斜杠，
			// 手机端无法解析）
			rel := strings.ReplaceAll(strings.Trim(reqPath, "/"), "\\", "/")
			if rel == "" {
				rel = e.Name()
			} else {
				rel = rel + "/" + e.Name()
			}
			items = append(items, fileEntry{
				Name:    e.Name(),
				IsDir:   e.IsDir(),
				Size:    fi.Size(),
				ModTime: fi.ModTime().Format(time.RFC3339),
				Path:    rel,
			})
		}
		// 目录在前，名称排序
		sort.Slice(items, func(i, j int) bool {
			if items[i].IsDir != items[j].IsDir {
				return items[i].IsDir
			}
			return strings.ToLower(items[i].Name) < strings.ToLower(items[j].Name)
		})
		writeJSON(w, listResp{Path: reqPath, Items: items})
	})(w, r)
}

// ---- API: 搜索（跨所有共享目录递归，按文件名包含匹配） ----

func (a *apiServer) handleSearch(w http.ResponseWriter, r *http.Request) {
	requireAuth(a.cfg, func(w http.ResponseWriter, r *http.Request) {
		q := strings.TrimSpace(r.URL.Query().Get("q"))
		if q == "" {
			http.Error(w, "请输入搜索关键词", http.StatusBadRequest)
			return
		}
		ql := strings.ToLower(q)
		roots := a.cfg.roots()
		results := make([]fileEntry, 0)
		const maxResults = 300
		for i, dir := range roots {
			_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
				if err != nil {
					if d != nil && d.IsDir() {
						return filepath.SkipDir
					}
					return nil
				}
				if d.IsDir() {
					// 跳过隐藏目录，避免把系统目录拖进来
					if strings.HasPrefix(d.Name(), ".") {
						return filepath.SkipDir
					}
					return nil
				}
				if len(results) >= maxResults {
					return filepath.SkipAll
				}
				if !strings.Contains(strings.ToLower(d.Name()), ql) {
					return nil
				}
				fi, err := d.Info()
				if err != nil {
					return nil
				}
				rel, err := filepath.Rel(dir, p)
				if err != nil {
					return nil
				}
				rel = filepath.ToSlash(rel)
				results = append(results, fileEntry{
					Name:    d.Name(),
					IsDir:   false,
					Size:    fi.Size(),
					ModTime: fi.ModTime().Format(time.RFC3339),
					Path:    fmt.Sprintf("%d/%s", i, rel),
				})
				return nil
			})
			if len(results) >= maxResults {
				break
			}
		}
		writeJSON(w, listResp{Path: "", Items: results})
	})(w, r)
}

// ---- API: 下载（支持 Range 断点续传/视频拖动） ----

func (a *apiServer) handleDownload(w http.ResponseWriter, r *http.Request) {
	requireAuth(a.cfg, func(w http.ResponseWriter, r *http.Request) {
		reqPath := r.URL.Query().Get("path")
		abs, err := a.safeJoin(reqPath)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		info, err := os.Stat(abs)
		if err != nil {
			if os.IsPermission(err) {
				http.Error(w, "没有权限读取该文件（系统文件需管理员）", http.StatusForbidden)
				return
			}
			http.Error(w, "文件不存在", http.StatusNotFound)
			return
		}
		if info.IsDir() {
			http.Error(w, "目录不支持直接下载", http.StatusBadRequest)
			return
		}
		// 防路径穿越 + 只读文件
		f, err := os.Open(abs)
		if err != nil {
			if os.IsPermission(err) {
				http.Error(w, "无法访问该文件（没有权限）", http.StatusForbidden)
				return
			}
			http.Error(w, "文件读取失败", http.StatusInternalServerError)
			return
		}
		defer f.Close()

		// 文件名：Content-Disposition 用 UTF-8 编码，供手机端正确保存中文名
		name := filepath.Base(abs)
		escaped := strings.ReplaceAll(name, `"`, `\"`)
		disposition := fmt.Sprintf(`attachment; filename="%s"; filename*=UTF-8''%s`,
			escaped, strings.ReplaceAll(urlEncodeName(name), "+", "%20"))
		w.Header().Set("Content-Disposition", disposition)
		w.Header().Set("Accept-Ranges", "bytes")

		// http.ServeContent 自动处理 Range/If-Modified-Since/Content-Type
		http.ServeContent(w, r, name, info.ModTime(), f)
	})(w, r)
}

// ---- API: 缩略图（列表微缩预览） ----

func (a *apiServer) handleThumb(w http.ResponseWriter, r *http.Request) {
	requireAuth(a.cfg, func(w http.ResponseWriter, r *http.Request) {
		reqPath := r.URL.Query().Get("path")
		abs, err := a.safeJoin(reqPath)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		// 仅常见图片格式生成缩略图，其它返回 404（手机端回退显示类型图标）
		ext := strings.ToLower(filepath.Ext(abs))
		switch ext {
		case ".jpg", ".jpeg", ".png", ".gif":
		default:
			http.Error(w, "不支持生成缩略图", http.StatusNotFound)
			return
		}
		f, err := os.Open(abs)
		if err != nil {
			if os.IsPermission(err) {
				http.Error(w, "无法访问该文件（没有权限）", http.StatusForbidden)
				return
			}
			http.Error(w, "文件不存在", http.StatusNotFound)
			return
		}
		defer f.Close()
		img, _, err := image.Decode(f)
		if err != nil {
			http.Error(w, "无法生成缩略图", http.StatusNotFound)
			return
		}
		// 等比缩放到最长边 256px
		src := img.Bounds()
		const maxSide = 256
		nw, nh := src.Dx(), src.Dy()
		if nw > maxSide || nh > maxSide {
			if nw >= nh {
				nh = nh * maxSide / nw
				nw = maxSide
			} else {
				nw = nw * maxSide / nh
				nh = maxSide
			}
		}
		if nw < 1 {
			nw = 1
		}
		if nh < 1 {
			nh = 1
		}
		dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
		// 简单近邻采样缩放（标准库无 x/image/draw，直接按比例采样像素）
		sw, sh := src.Dx(), src.Dy()
		for y := 0; y < nh; y++ {
			sy := y * sh / nh
			for x := 0; x < nw; x++ {
				dst.Set(x, y, img.At(x*sw/nw, sy))
			}
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		jpeg.Encode(w, dst, &jpeg.Options{Quality: 80})
	})(w, r)
}

// urlEncodeName URL 编码文件名（不带 QueryEscape 的空格转义问题）
func urlEncodeName(s string) string {
	var sb strings.Builder
	for _, b := range []byte(s) {
		if (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') ||
			b == '-' || b == '_' || b == '.' || b == '~' {
			sb.WriteByte(b)
		} else {
			fmt.Fprintf(&sb, "%%%02X", b)
		}
	}
	return sb.String()
}

// ---- API: 上传（手机 → 电脑，手机 APP 配套使用） ----

func (a *apiServer) handleUpload(w http.ResponseWriter, r *http.Request) {
	requireAuth(a.cfg, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		dirPath := r.URL.Query().Get("path")      // 目标目录（相对共享根）
		fileName := r.URL.Query().Get("filename") // 文件名
		if fileName == "" {
			http.Error(w, "缺少 filename 参数", http.StatusBadRequest)
			return
		}
		// 文件名安全化：只保留 basename，杜绝路径注入
		fileName = strings.ReplaceAll(fileName, "\\", "/")
		fileName = filepath.Base(fileName)
		if fileName == "." || fileName == ".." || fileName == "" {
			http.Error(w, "非法文件名", http.StatusBadRequest)
			return
		}
		dirAbs, err := a.safeJoin(dirPath)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if st, err := os.Stat(dirAbs); err != nil || !st.IsDir() {
			if dirAbs == "" {
				http.Error(w, "请先进入某个共享目录，再上传文件", http.StatusBadRequest)
				return
			}
			http.Error(w, "目标目录不存在", http.StatusBadRequest)
			return
		}
		dest := filepath.Join(dirAbs, fileName)
		out, err := os.Create(dest)
		if err != nil {
			http.Error(w, "创建文件失败: "+err.Error(), http.StatusInternalServerError)
			return
		}
		n, cErr := io.Copy(out, r.Body)
		_ = out.Close()
		if cErr != nil {
			_ = os.Remove(dest) // 写一半失败，清理半成品
			http.Error(w, "写入失败: "+cErr.Error(), http.StatusInternalServerError)
			return
		}
		log.Printf("上传完成: %s（%d 字节）", dest, n)
		writeJSON(w, map[string]any{"ok": "上传成功", "size": n})
	})(w, r)
}

// ---- 通用 ----

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}

func writeOK(w http.ResponseWriter, msg string) {
	writeJSON(w, map[string]string{"ok": msg})
}

// ---- 配置管理（仅本机） ----

type configPayload struct {
	RootDir  string   `json:"rootDir"`
	Dirs     []string `json:"dirs"`
	Username string   `json:"username"`
	Password string   `json:"password"`
}

func (a *apiServer) handleConfig(w http.ResponseWriter, r *http.Request) {
	onlyLocal(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var p configPayload
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			http.Error(w, "JSON 解析失败: "+err.Error(), http.StatusBadRequest)
			return
		}
		// 多目录优先；兼容只传 rootDir 的旧客户端
		dirs := p.Dirs
		if len(dirs) == 0 && p.RootDir != "" {
			dirs = []string{p.RootDir}
		}
		clean := make([]string, 0, len(dirs))
		for _, d := range dirs {
			nd := normalizePath(d)
			if nd == "" {
				continue
			}
			if st, err := os.Stat(nd); err != nil || !st.IsDir() {
				http.Error(w, "共享目录不存在或不是文件夹: "+nd, http.StatusBadRequest)
				return
			}
			clean = append(clean, nd)
		}
		if len(clean) == 0 {
			http.Error(w, "共享目录不能为空（至少填写一个文件夹）", http.StatusBadRequest)
			return
		}
		if len(p.Username) < 1 || len(p.Password) < 4 {
			http.Error(w, "用户名至少 1 位、密码至少 4 位", http.StatusBadRequest)
			return
		}
		a.cfg.Dirs = clean
		a.cfg.RootDir = clean[0]
		a.cfg.Username = p.Username
		a.cfg.Password = p.Password
		if err := a.cfg.saveConfig(); err != nil {
			http.Error(w, "保存配置失败: "+err.Error(), http.StatusInternalServerError)
			return
		}
		log.Printf("配置已更新：共享目录=%d 个（%s…），用户名=%s", len(clean), clean[0], a.cfg.Username)
		writeOK(w, "配置已保存")
	})(w, r)
}

// ---- 开机自启（仅本机） ----

type autostartPayload struct {
	Action string `json:"action"` // install | remove
}

func (a *apiServer) handleAutostart(w http.ResponseWriter, r *http.Request) {
	onlyLocal(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var p autostartPayload
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			http.Error(w, "JSON 解析失败", http.StatusBadRequest)
			return
		}
		var err error
		switch p.Action {
		case "install":
			err = installAutostart()
			if err != nil {
				http.Error(w, "注册失败（可能需要管理员权限，请右键程序以管理员身份运行）："+err.Error(), http.StatusInternalServerError)
				return
			}
			writeOK(w, "已注册：开机自动启动信鸽")
		case "remove":
			err = removeAutostart()
			if err != nil {
				http.Error(w, "移除失败: "+err.Error(), http.StatusInternalServerError)
				return
			}
			writeOK(w, "已移除开机自启")
		default:
			http.Error(w, "action 必须为 install 或 remove", http.StatusBadRequest)
		}
	})(w, r)
}

// ---- Tailscale 组网状态（本机可看，手机端不需要） ----

func (a *apiServer) handleTSStatus(w http.ResponseWriter, r *http.Request) {
	onlyLocal(func(w http.ResponseWriter, r *http.Request) {
		st := a.ts.status()
		writeJSON(w, st)
	})(w, r)
}

func (a *apiServer) handleTSLogin(w http.ResponseWriter, r *http.Request) {
	onlyLocal(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		url := a.ts.triggerLogin()
		if url == "" {
			writeJSON(w, map[string]string{
				"status": "started",
				"msg":    "已启动组网登录流程，请稍候刷新状态页查看登录链接",
			})
			return
		}
		writeJSON(w, map[string]string{"status": "need_login", "url": url})
	})(w, r)
}

// ---- 配置页根路由 ----

func (a *apiServer) handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" && !strings.HasPrefix(r.URL.Path, "/static/") {
		http.NotFound(w, r)
		return
	}
	onlyLocal(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			_, _ = w.Write([]byte(configPageHTML(a.cfg, listenPort)))
			return
		}
		http.NotFound(w, r)
	})(w, r)
}

// str2int 工具
func str2int(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}
