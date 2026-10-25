package main

import (
	"archive/zip"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

const APP_DIR = "FileBridge"

// magicHeader 构建时写入 zip 数据前的固定标记（见 build.sh），用于精确定位内嵌 zip
var magicHeader = []byte("FBZIP-MAGIC-V1\x00")

// selfPayload 读取自身 exe 中内嵌的 zip 数据（自解压：payload 在构建时 cat 到 exe 末尾）
func selfPayload() ([]byte, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	f, err := os.Open(exe)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	data, err := io.ReadAll(f)
	if err != nil {
		return nil, err
	}
	idx := bytes.LastIndex(data, magicHeader)
	if idx < 0 || idx+len(magicHeader) >= len(data) {
		return nil, errors.New("安装程序损坏：找不到内嵌数据（请重新下载）")
	}
	return data[idx+len(magicHeader):], nil
}

var (
	user32        = syscall.NewLazyDLL("user32.dll")
	procMessageBox = user32.NewProc("MessageBoxW")
)

const (
	MB_OK               = 0x00000000
	MB_OKCANCEL         = 0x00000001
	MB_ICONINFORMATION  = 0x00000040
	MB_ICONQUESTION     = 0x00000020
	MB_ICONERROR        = 0x00000010
)

func msgBox(text, title string, flags uintptr) int {
	r, _, _ := procMessageBox.Call(
		0,
		uintptr(unsafe.Pointer(syscall.StringToUTF16Ptr(text))),
		uintptr(unsafe.Pointer(syscall.StringToUTF16Ptr(title))),
		flags,
	)
	return int(r)
}

func main() {
	// 安装目标：%LOCALAPPDATA%\FileBridge
	local := os.Getenv("LOCALAPPDATA")
	if local == "" {
		local = filepath.Join(os.Getenv("USERPROFILE"), "AppData", "Local")
	}
	installDir := filepath.Join(local, APP_DIR)

	// 如果已经安装了，先问是否覆盖重装
	if _, err := os.Stat(filepath.Join(installDir, "FileBridge.exe")); err == nil {
		r := msgBox(
			"检测到 FileBridge 已安装。\n是否重新安装（覆盖旧版本）？",
			"FileBridge 安装程序", MB_OKCANCEL|MB_ICONQUESTION)
		if r != 1 {
			msgBox("已取消安装。", "FileBridge 安装程序", MB_OK|MB_ICONINFORMATION)
			return
		}
	}

	// 解压
	payload, err := selfPayload()
	if err != nil {
		msgBox("安装失败：\n"+err.Error(), "FileBridge 安装程序", MB_OK|MB_ICONERROR)
		return
	}
	if err := extractPayload(payload, installDir); err != nil {
		msgBox("安装失败：\n"+err.Error(), "FileBridge 安装程序", MB_OK|MB_ICONERROR)
		return
	}

	// 桌面/开始菜单快捷方式
	desktop := desktopPath()
	startMenu := startMenuPath()
	lnkTarget := filepath.Join(installDir, "FileBridge.exe")
	_ = createShortcut(filepath.Join(desktop, "FileBridge.lnk"), lnkTarget, installDir)
	_ = createShortcut(filepath.Join(startMenu, "FileBridge.lnk"), lnkTarget, installDir)

	// 写入卸载注册表（控制面板可卸载）
	_ = registerUninstall(installDir, desktop, startMenu)

	msgBox(
		"安装完成！\n\n已安装到："+installDir+"\n已创建桌面和开始菜单快捷方式。\n\n即将启动 FileBridge。",
		"FileBridge 安装程序", MB_OK|MB_ICONINFORMATION)

	// 启动
	cmd := exec.Command(lnkTarget)
	_ = cmd.Start()
}

// extractPayload 解压内嵌 zip 到目标目录（去掉顶层文件夹前缀）
func extractPayload(payload []byte, dst string) error {
	zr, err := zip.NewReader(bytes.NewReader(payload), int64(len(payload)))
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dst, 0o755); err != nil {
		return err
	}
	for _, f := range zr.File {
		name := f.Name
		// 去掉顶层目录（如 FileBridge-桌面版/）
		if i := strings.Index(name, "/"); i >= 0 {
			name = name[i+1:]
		}
		if name == "" || strings.Contains(name, "..") {
			continue
		}
		target := filepath.Join(dst, filepath.FromSlash(name))
		if f.FileInfo().IsDir() {
			_ = os.MkdirAll(target, 0o755)
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(target)
		if err != nil {
			rc.Close()
			return err
		}
		_, copyErr := io.Copy(out, rc)
		rc.Close()
		out.Close()
		if copyErr != nil {
			return copyErr
		}
	}
	return nil
}

func desktopPath() string {
	if d := os.Getenv("USERPROFILE"); d != "" {
		p := filepath.Join(d, "Desktop")
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return filepath.Join(os.Getenv("USERPROFILE"), "Desktop")
}

func startMenuPath() string {
	base := filepath.Join(os.Getenv("APPDATA"), "Microsoft", "Windows", "Start Menu", "Programs")
	if _, err := os.Stat(base); err != nil {
		return os.Getenv("USERPROFILE")
	}
	return base
}

// createShortcut 用 PowerShell 创建 .lnk
func createShortcut(lnkPath, target, workDir string) error {
	ps := fmt.Sprintf(
		`$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut('%s'); $s.TargetPath = '%s'; $s.WorkingDirectory = '%s'; $s.IconLocation = '%s,0'; $s.Save()`,
		lnkPath, target, workDir, target)
	return exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps).Run()
}

// registerUninstall 写控制面板卸载项（UninstallString 用 PowerShell 删除安装目录与快捷方式）
func registerUninstall(installDir, desktop, startMenu string) error {
	uninstallPs := fmt.Sprintf(
		`Remove-Item -Recurse -Force '%s'; Remove-Item -Force '%s'; Remove-Item -Force '%s'; Remove-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\FileBridge' -Force`,
		installDir,
		filepath.Join(desktop, "FileBridge.lnk"),
		filepath.Join(startMenu, "FileBridge.lnk"),
	)
	uninstallCmd := fmt.Sprintf(
		`powershell -NoProfile -ExecutionPolicy Bypass -Command "%s"`,
		strings.ReplaceAll(uninstallPs, `"`, `\"`),
	)
	key := `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\FileBridge`
	_ = exec.Command("reg", "add", key, "/v", "DisplayName", "/t", "REG_SZ", "/d", "FileBridge 电脑端", "/f").Run()
	_ = exec.Command("reg", "add", key, "/v", "DisplayVersion", "/t", "REG_SZ", "/d", "4.2", "/f").Run()
	_ = exec.Command("reg", "add", key, "/v", "Publisher", "/t", "REG_SZ", "/d", "FileBridge", "/f").Run()
	_ = exec.Command("reg", "add", key, "/v", "InstallLocation", "/t", "REG_SZ", "/d", installDir, "/f").Run()
	_ = exec.Command("reg", "add", key, "/v", "UninstallString", "/t", "REG_SZ", "/d", uninstallCmd, "/f").Run()
	_ = exec.Command("reg", "add", key, "/v", "DisplayIcon", "/t", "REG_SZ", "/d", filepath.Join(installDir, "FileBridge.exe")+",0", "/f").Run()
	return nil
}
