//go:build darwin

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"text/template"
)

const taskName = "FileBridgeServer"

// 开机自启：通过 launchd（LaunchAgents plist）实现，与 Windows 的"重启后自动拉起"一致
const launchAgentPath = "Library/LaunchAgents/com.filebridge.server.plist"

const launchAgentTpl = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.filebridge.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>{{.Exe}}</string>
        <string>--daemon</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>ProcessType</key>
    <string>Background</string>
    <key>StandardErrorPath</key>
    <string>{{.Home}}/.filebridge/launchd.err.log</string>
    <key>StandardOutPath</key>
    <string>{{.Home}}/.filebridge/launchd.out.log</string>
</dict>
</plist>
`

func selfExePath() (string, error) {
	if exe, err := os.Executable(); err == nil {
		return exe, nil
	}
	return "", fmt.Errorf("无法定位程序自身路径")
}

// installAutostart 注册 launchd 开机自启
func installAutostart() error {
	exe, err := selfExePath()
	if err != nil {
		return err
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	plistPath := filepath.Join(home, launchAgentPath)
	if err := os.MkdirAll(filepath.Dir(plistPath), 0o755); err != nil {
		return err
	}
	_ = os.MkdirAll(filepath.Join(home, ".filebridge"), 0o700)

	data, err := os.Create(plistPath)
	if err != nil {
		return err
	}
	t := template.Must(template.New("plist").Parse(launchAgentTpl))
	if err := t.Execute(data, map[string]string{"Exe": exe, "Home": home}); err != nil {
		data.Close()
		return err
	}
	data.Close()

	// 加载：优先新版 bootstrap，失败回退 load
	bootErr := exec.Command("launchctl", "bootstrap", "gui/"+fmt.Sprint(os.Getuid()), plistPath).Run()
	if bootErr != nil {
		_ = exec.Command("launchctl", "load", plistPath).Run()
	}
	return nil
}

// removeAutostart 移除 launchd 开机自启
func removeAutostart() error {
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	plistPath := filepath.Join(home, launchAgentPath)
	_ = exec.Command("launchctl", "bootout", "gui/"+fmt.Sprint(os.Getuid()), plistPath).Run()
	_ = exec.Command("launchctl", "unload", plistPath).Run()
	return os.Remove(plistPath)
}

// hideConsole macOS 无控制台窗口概念，无需处理
func hideConsole() {}
