package main

import (
	"fmt"
	"strings"
)

// configPageHTML 本机配置页：首次设置 + 状态查看 + 自启 + Tailscale 登录
func configPageHTML(c *Config, port int) string {
	dirs := c.roots()
	user := c.Username
	passSet := c.Password != ""
	token := ""
	if c.completed() {
		token = c.tokenValue()
	}
	completed := c.completed()

	dirsText := strings.Join(dirs, "\n")
	if !completed {
		dirsText = ""
	}
	fsRoot := strings.Join(dirs, "<br>")
	if !completed {
		fsRoot = "（尚未设置）"
	}

	return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>FileBridge 电脑端设置</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f5f7fa;color:#1f2937;padding:24px 12px}
  .wrap{max-width:640px;margin:0 auto}
  h1{font-size:22px;margin-bottom:6px}
  .sub{color:#6b7280;font-size:13px;margin-bottom:20px}  .card{background:#fff;border-radius:12px;padding:18px;margin-bottom:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
  .card h2{font-size:15px;margin-bottom:12px}
  .row{margin-bottom:12px}
  .row label{display:block;font-size:13px;color:#374151;margin-bottom:4px}
  .row input[type=text],.row input[type=password],.row textarea{width:100%;padding:9px 10px;border:1px solid #d1d5db;border-radius:8px;font-size:14px;font-family:inherit}
  .row textarea{resize:vertical;line-height:1.6}
  .row input[type=text]:focus,.row input[type=password]:focus,.row textarea:focus{outline:none;border-color:#2563eb}
  .btn{display:inline-block;padding:9px 18px;border:none;border-radius:8px;font-size:14px;cursor:pointer;margin-right:8px}
  .btn-primary{background:#2563eb;color:#fff}
  .btn-primary:hover{background:#1d4ed8}
  .btn-gray{background:#e5e7eb;color:#374151}
  .btn-gray:hover{background:#d1d5db}
  .btn-green{background:#059669;color:#fff}
  .btn-green:hover{background:#047857}
  .tip{font-size:12.5px;color:#6b7280;margin-top:6px;line-height:1.6}
  .state{font-size:13px;line-height:1.8}
  .state b{color:#111827}
  .ok{color:#059669;font-weight:600}
  .warn{color:#d97706;font-weight:600}
  .bad{color:#dc2626;font-weight:600}
  .code{background:#f3f4f6;padding:2px 6px;border-radius:4px;font-family:Consolas,Monaco,monospace;font-size:12.5px;word-break:break-all}
  .msg{padding:10px 12px;border-radius:8px;font-size:13px;margin-top:10px;display:none}
  .msg.show{display:block}
  .msg.ok{background:#ecfdf5;color:#047857;border:1px solid #a7f3d0}
  .msg.err{background:#fef2f2;color:#b91c1c;border:1px solid #fecaca}
  .hidden{display:none}
</style>
</head>
<body>
<div class="wrap">
<h1>FileBridge 电脑端设置</h1>
<div class="sub">版本 v` + version + ` · 配置一次，之后重启自动运行。设置页仅供本机访问。</div>

<div class="card">
  <h2>① 当前状态</h2>
  <div class="state" id="stateBox">
    共享目录：<b>` + fsRoot + `</b><br>
    访问凭据：` + fmt.Sprintf("%s", func() string { if completed { return "已设置（用户名 " + user + "）" }; return "<span class='warn'>未设置</span>" }()) + `<br>
    局域网访问地址：<span class="code">http://电脑IP:` + fmt.Sprintf("%d", port) + `</span><br>
    配置文件：<span class="code">` + htmlEscape(configPath) + `</span><br>
    组网状态：<span id="tsState">检测中…</span>
  </div>
  <div class="msg" id="statusMsg"></div>
</div>

<div class="card">
  <h2>② 共享设置</h2>
  <div class="row">
    <label>共享目录（每行一个文件夹，可添加多个；手机端打开时会先看到这些目录的列表）</label>
    <textarea id="rootDir" rows="4" placeholder="例如：&#10;D:\共享文件&#10;E:\电影&#10;C:\Users\你的名字\Documents">` + htmlEscape(dirsText) + `</textarea>
  </div>
  <div class="row">
    <label>访问用户名</label>
    <input type="text" id="username" value="` + htmlEscape(user) + `" placeholder="建议英文，如 fileuser">
  </div>
  <div class="row">
    <label>访问密码（手机端需要输入，至少 4 位）</label>
    <input type="password" id="password" placeholder="` + func() string { if passSet { return "（已设置，输入新密码可修改）" }; return "" }() + `">
  </div>
  <button class="btn btn-primary" onclick="saveConfig()">保存设置</button>
  <div class="tip">保存后手机端即可通过 用户名/密码 访问（会先看到所有共享目录的列表）；token 值为 <span class="code" id="tokenShow">` + token + `</span></div>
  <div class="msg" id="cfgMsg"></div>
</div>

<div class="card">
  <h2>③ 开机自启</h2>
  <div>开启后，电脑重启会自动运行 FileBridge，无需手动打开。</div>
  <div style="margin-top:10px">
    <button class="btn btn-green" onclick="autostart('install')">开启开机自启</button>
    <button class="btn btn-gray" onclick="autostart('remove')">关闭开机自启</button>
  </div>
  <div class="tip">如提示需要管理员权限，请右键 FileBridge 程序 → 以管理员身份运行 → 再点一次。</div>
  <div class="msg" id="asMsg"></div>
</div>

<div class="card">
  <h2>④ 外网访问（Tailscale 组网）</h2>
  <div class="state" id="tsDetail">点击下方按钮启动组网。</div>
  <div style="margin-top:10px">
    <button class="btn btn-primary" onclick="tsLogin()">连接 Tailscale</button>
    <button class="btn btn-gray" onclick="tsStatus()">刷新状态</button>
  </div>
  <div class="tip">原理：电脑加入 Tailscale 私有网络，手机装 Tailscale APP 登录同一账号即可在任意网络访问电脑，全程加密、不暴露公网端口。<br>
  连接后在这里会显示电脑的组网地址（形如 <span class="code">http://100.x.x.x:8123</span>），把它填进手机 APP 即可。</div>
  <div class="msg" id="tsMsg"></div>
</div>

<div class="card">
  <h2>手机 APP 使用方法</h2>
  <div class="tip">
  ① 手机安装 Tailscale APP，登录与电脑端<b>同一个账号</b>；<br>
  ② 打开 FileBridge 手机 APP，添加服务器：地址填本页的 <b>局域网地址</b>（家里）或 <b>组网地址</b>（外网），端口 8123；<br>
  ③ 输入上面设置的 用户名/密码，即可浏览、下载、用手机自带软件打开文件。
  </div>
</div>
</div>

<script>
const $=id=>document.getElementById(id);
function showMsg(id,text,ok){const m=$(id);m.textContent=text;m.className='msg show '+(ok?'ok':'err');setTimeout(()=>m.className='msg',4000);}
async function api(url,opts){const r=await fetch(url,opts);const t=await r.text();if(!r.ok)throw new Error(t||('HTTP '+r.status));try{return JSON.parse(t)}catch(e){return t}}
function saveConfig(){
  const dirs=$('rootDir').value.split('\n').map(s=>s.trim()).filter(Boolean);
  if(!dirs.length){showMsg('cfgMsg','请至少填写一个共享目录',false);return}
  if(!$('username').value.trim()){showMsg('cfgMsg','请填写用户名',false);return}
  if(!$('password').value){showMsg('cfgMsg','请填写密码（至少4位）',false);return}
  const body={dirs,username:$('username').value.trim(),password:$('password').value};
  api('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(()=>{showMsg('cfgMsg','已保存！手机端现在可以连接了',true);location.reload()})
    .catch(e=>showMsg('cfgMsg','保存失败：'+e.message,false));
}
function autostart(action){
  api('/api/autostart',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action})})
    .then(d=>showMsg('asMsg',d.ok||'成功',true))
    .catch(e=>showMsg('asMsg',e.message,false));
}
function tsStatus(){
  api('/api/tailscale/status').then(d=>{
    const ip=d.ip||'（未连接）';
    $('tsState').innerHTML=d.loggedIn?'已连接':'未连接';
    $('tsState').className=d.loggedIn?'ok':'warn';
    $('tsDetail').innerHTML='状态：'+(d.loggedIn?'<b>已连接</b>':'<b>未连接</b>')+'<br>组网地址：<span class="code">http://'+(d.ip||'-')+':8123</span><br>'+(d.hostname?('主机名：<span class="code">'+d.hostname+'</span>'):'');
    if(d.loginUrl){$('tsMsg').innerHTML='请点击打开登录链接完成授权：<br><a href="'+d.loginUrl+'" target="_blank" class="code">'+d.loginUrl+'</a><br>登录完成后点击“刷新状态”。';$('tsMsg').className='msg show';}
  }).catch(e=>{$('tsState').textContent='查询失败';$('tsState').className='bad';});
}
function tsLogin(){
  api('/api/tailscale/login',{method:'POST'})
    .then(d=>{
      if(d.url){$('tsMsg').innerHTML='请点击打开登录链接完成授权：<br><a href="'+d.url+'" target="_blank" class="code">'+d.url+'</a><br>登录完成后点击“刷新状态”。';$('tsMsg').className='msg show';}
      else{showMsg('tsMsg',d.msg||'已启动，请稍候刷新',true);setTimeout(tsStatus,3000);}
    })
    .catch(e=>showMsg('tsMsg',e.message,false));
}
tsStatus();
setInterval(tsStatus,8000);
</script>
</body>
</html>`
}

func htmlEscape(s string) string {
	return strings.NewReplacer(
		"&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&#39;",
	).Replace(s)
}
