//go:build !windows && !darwin

package main

import "fmt"

const taskName = "FileBridgeServer"

// installAutostart 非 Windows 平台占位（用于本地开发验证）
func installAutostart() error {
	return fmt.Errorf("开机自启仅在 Windows 上可用")
}

// removeAutostart 非 Windows 平台占位
func removeAutostart() error {
	return nil
}

// hideConsole 非 Windows 平台占位
func hideConsole() {}
