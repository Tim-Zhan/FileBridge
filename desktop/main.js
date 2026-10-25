/**
 * FileBridge 电脑端桌面控制台（Electron，Windows / macOS 通用）
 *
 * 职责：
 *  - 一键启动同目录的文件服务（filebridge-server，已内含 Tailscale 组网）
 *  - 窗口直接加载本机配置页 http://127.0.0.1:8123/ ，不再需要浏览器
 *  - 关闭窗口时最小化到系统托盘（macOS 为菜单栏图标），服务继续后台运行
 *  - 托盘菜单：打开设置面板 / 退出（停止服务）
 *
 * 排障：启动过程会写入 ~/.filebridge/desktop.log
 */
const { app, BrowserWindow, Tray, Menu, dialog, nativeImage } = require('electron');
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const IS_MAC = process.platform === 'darwin';
const SERVICE_PORT = 8123;
const SERVICE_URL = `http://127.0.0.1:${SERVICE_PORT}/`;
const SERVICE_EXE = IS_MAC ? 'pigeonfile-server' : 'pigeonfile-server.exe';
const ICON = path.join(__dirname, 'icon.png');

let win = null;
let tray = null;
let serviceProc = null;
let quitting = false;
let loadTimer = null;

// ---- 日志（排障用）----
const LOG_FILE = path.join(os.homedir(), '.filebridge', 'desktop.log');
function log(msg) {
  try {
    const line = `[${new Date().toLocaleString()}] ${msg}\n`;
    fs.appendFileSync(LOG_FILE, line);
  } catch (_) {}
}

// ---- 稳定性兜底：软件渲染，避免 GPU 问题导致窗口黑屏/不显示 ----
app.disableHardwareAcceleration();

// 单实例：重复双击只唤醒已有窗口
if (!app.requestSingleInstanceLock()) {
  log('已有实例在运行，本实例退出');
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
}

function servicePath() {
  // 打包后与服务 exe 同目录；开发时回退到 ../dist
  const exe = path.join(path.dirname(process.execPath), SERVICE_EXE);
  if (fs.existsSync(exe)) return exe;
  return path.join(__dirname, '..', 'dist', SERVICE_EXE);
}

function isServiceUp() {
  return new Promise((resolve) => {
    const req = http.get(SERVICE_URL, { timeout: 1500 }, () => resolve(true));
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

async function ensureService() {
  if (await isServiceUp()) {
    log('服务已在运行，无需重新启动');
    return;
  }
  try {
    log('启动服务: ' + servicePath());
    serviceProc = spawn(servicePath(), [], { windowsHide: !IS_MAC });
  } catch (e) {
    log('启动服务失败: ' + e.message);
    dialog.showErrorBox('信鸽', `无法启动服务：${e.message}`);
    return;
  }
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setTimeout(r, 500));
    if (await isServiceUp()) {
      log('服务已就绪');
      return;
    }
  }
  log('等待 20 秒后服务仍未就绪');
}

function showWindow() {
  if (win && !win.isDestroyed()) {
    win.show();
    win.focus();
    return;
  }
  createWindow();
}

/** 载入配置页；失败则显示占位提示，稍后自动重试 */
function loadSettingsPage() {
  if (!win || win.isDestroyed()) return;
  win.loadURL(SERVICE_URL).catch(() => {
    win.loadURL(
      'data:text/html;charset=utf-8,' +
        encodeURIComponent(
          '<div style="font-family:system-ui;color:#333;text-align:center;margin-top:38%;">' +
            '<h2>信鸽 · 电脑端</h2>' +
            '<p style="color:#888;">正在启动文件服务，请稍候…</p>' +
            '<p style="color:#bbb;font-size:13px;">本窗口会自动加载设置页，无需手动操作</p>' +
            '</div>'
        )
    );
    clearTimeout(loadTimer);
    loadTimer = setTimeout(loadSettingsPage, 1500);
  });
}

function createWindow() {
  log('创建主窗口');
  win = new BrowserWindow({
    width: 880,
    height: 760,
    minWidth: 620,
    minHeight: 560,
    title: '信鸽 · 电脑端',
    autoHideMenuBar: !IS_MAC,
    icon: IS_MAC ? undefined : ICON,
    backgroundColor: '#f5f7fa',
  });
  win.show();
  win.focus();

  win.loadURL(
    'data:text/html;charset=utf-8,' +
      encodeURIComponent(
        '<div style="font-family:system-ui;color:#333;text-align:center;margin-top:38%;">' +
          '<h2>信鸽 · 电脑端</h2>' +
          '<p style="color:#888;">正在启动文件服务，请稍候…</p>' +
          '</div>'
      )
  );
  loadSettingsPage();

  // 渲染进程崩溃/卡死时自动重建窗口
  win.webContents.on('render-process-gone', () => {
    log('渲染进程异常，重建窗口');
    setTimeout(showWindow, 500);
  });
  win.webContents.on('unresponsive', () => {
    log('页面无响应，尝试重载');
    win.reload();
  });

  // 关闭 = 最小化到托盘（服务继续后台运行）
  win.on('close', (e) => {
    if (!quitting) {
      e.preventDefault();
      win.hide();
      log('窗口已最小化到托盘');
    }
  });
  win.on('closed', () => { win = null; });
}

function createTray() {
  const icon = nativeImage.createFromPath(ICON);
  tray = new Tray(icon);
  tray.setToolTip('信鸽 · 电脑端（正在后台运行）');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: '打开设置面板', click: () => showWindow() },
      {
        label: '退出（停止服务）',
        click: () => {
          quitting = true;
          try { if (serviceProc) serviceProc.kill(); } catch (_) {}
          app.quit();
        },
      },
    ])
  );
  tray.on('click', () => {
    if (!win || win.isDestroyed()) createWindow();
    win.isVisible() ? win.hide() : win.show();
  });
}

app.whenReady().then(() => {
  log('应用就绪，创建窗口与托盘');
  createWindow();
  createTray();
  ensureService();
});

// macOS：点击 Dock 图标时唤出窗口
app.on('activate', () => {
  if (!win || win.isDestroyed()) createWindow();
  win.show();
  win.focus();
});

// 关掉所有窗口也不退出：托盘常驻
app.on('window-all-closed', () => {});
app.on('before-quit', () => { quitting = true; });
