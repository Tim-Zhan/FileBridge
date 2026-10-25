//go:build windows

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"

	"golang.org/x/sys/windows/registry"
)

const runValueName = "FileBridgeServer"

// exePath 当前可执行文件路径
func exePath() (string, error) {
	p, err := os.Executable()
	if err != nil {
		return "", err
	}
	return p, nil
}

// installAutostart 注册当前用户的开机自启（注册表 Run 键）。
// 使用 HKCU Run（而非计划任务）：这是普通绿色软件最常见的自启方式，
// 不需要管理员权限，%USERPROFILE% 与双击运行完全一致，也不会被安全软件
// 当作"计划任务持久化"类高危行为重点拦截。
func installAutostart() error {
	exe, err := exePath()
	if err != nil {
		return err
	}
	key, _, err := registry.CreateKey(
		registry.CURRENT_USER,
		`Software\Microsoft\Windows\CurrentVersion\Run`,
		registry.SET_VALUE,
	)
	if err != nil {
		return fmt.Errorf("无法打开注册表自启项: %v", err)
	}
	defer key.Close()
	if err := key.SetStringValue(runValueName, fmt.Sprintf(`"%s" --daemon`, exe)); err != nil {
		return fmt.Errorf("写入自启注册失败: %v", err)
	}
	return nil
}

// removeAutostart 移除自启注册项（只删自己的值，不动其它自启项）
func removeAutostart() error {
	key, err := registry.OpenKey(
		registry.CURRENT_USER,
		`Software\Microsoft\Windows\CurrentVersion\Run`,
		registry.SET_VALUE,
	)
	if err != nil {
		return nil // 键不存在视为已移除
	}
	defer key.Close()
	_ = key.DeleteValue(runValueName)
	return nil
}

// hideConsole 隐藏控制台窗口（守护模式）
func hideConsole() {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	user32 := syscall.NewLazyDLL("user32.dll")
	getConsoleWindow := kernel32.NewProc("GetConsoleWindow")
	showWindow := user32.NewProc("ShowWindow")
	if hwnd, _, _ := getConsoleWindow.Call(); hwnd != 0 {
		showWindow.Call(hwnd, 0) // SW_HIDE
	}
}

// daemonDir 供守护模式使用的运行目录（exe 所在目录）
func daemonDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}
