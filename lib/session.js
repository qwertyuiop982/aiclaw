'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const configMod = require('./config');
function dir() { return path.join(os.homedir(), '.aiclaw', 'sessions'); }
function filePath(name) { return path.join(dir(), name + '.jsonl'); }
function ensure() {
  const d = dir();
  if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true, mode: 0o700 });
}
function list() {
  ensure();
  const cfg = configMod.load();
  const cur = cfg.currentSession || 'default';
  const out = [];
  const files = fs.readdirSync(dir()).filter(f => f.endsWith('.jsonl'));
  for (const f of files) {
    const full = path.join(dir(), f);
    const stat = fs.statSync(full);
    const name = f.replace(/\.jsonl$/, '');
    let n = 0;
    let lastTs = 0;
    try {
      const content = fs.readFileSync(full, 'utf8');
      const lines = content.split(/\r?\n/).filter(Boolean);
      n = lines.length;
      if (lines.length) {
        try { const o = JSON.parse(lines[lines.length - 1]); if (o.ts) lastTs = o.ts; } catch (e) {}
      }
    } catch (e) {}
    out.push({ name, messages: n, mtime: stat.mtimeMs, lastTs, current: (name === cur) });
  }
  out.sort((a, b) => b.mtime - a.mtime);
  return out;
}
function getCurrentName() {
  const cfg = configMod.load();
  if (!cfg.currentSession) {
    cfg.currentSession = 'default';
    configMod.save(cfg);
    ensure();
    const p = filePath('default');
    if (!fs.existsSync(p)) fs.writeFileSync(p, '', { mode: 0o600 });
  }
  return cfg.currentSession;
}
function setCurrent(name) {
  if (!name || !/^[\w\-\.\u4e00-\u9fa5]+$/.test(name)) {
    throw new Error('session name: only letters/digits/_/-/. and CJK allowed');
  }
  ensure();
  const p = filePath(name);
  if (!fs.existsSync(p)) fs.writeFileSync(p, '', { mode: 0o600 });
  const cfg = configMod.load();
  cfg.currentSession = name;
  configMod.save(cfg);
  return name;
}
function exists(name) { ensure(); return fs.existsSync(filePath(name)); }
function drop(name) {
  ensure();
  const p = filePath(name);
  if (!fs.existsSync(p)) throw new Error('session not found: ' + name);
  fs.unlinkSync(p);
  if (getCurrentName() === name) {
    const cfg = configMod.load();
    cfg.currentSession = '';
    configMod.save(cfg);
    setCurrent('default');
  }
}
function clear(name) {
  ensure();
  const target = name || getCurrentName();
  const p = filePath(target);
  fs.writeFileSync(p, '', { mode: 0o600 });
  return target;
}
function loadMessages(name) {
  ensure();
  const target = name || getCurrentName();
  const p = filePath(target);
  if (!fs.existsSync(p)) {
    fs.writeFileSync(p, '', { mode: 0o600 });
    return [];
  }
  const content = fs.readFileSync(p, 'utf8');
  const msgs = [];
  for (const line of content.split(/\r?\n/)) {
    if (!line.trim()) continue;
    try { const o = JSON.parse(line); msgs.push(o); } catch (e) {}
  }
  return msgs;
}
function appendMessage(name, msg) {
  ensure();
  const target = name || getCurrentName();
  const p = filePath(target);
  if (!fs.existsSync(p)) fs.writeFileSync(p, '', { mode: 0o600 });
  const o = Object.assign({}, msg, { ts: msg.ts || Date.now() });
  fs.appendFileSync(p, JSON.stringify(o) + '\n', { mode: 0o600 });
  return o;
}
function setSystem(name, content) {
  // 覆盖当前会话的 system 提示（仅 1 条），保留其他消息原 ts，不刷新 ts。
  ensure();
  const target = name || getCurrentName();
  const msgs = loadMessages(target);
  const sysIdx = msgs.findIndex(m => m.role === 'system');
  if (sysIdx >= 0) msgs.splice(sysIdx, 1);
  const now = Date.now();
  if (content && content.trim()) {
    msgs.unshift({ role: 'system', content: content, ts: now });
  }
  const p = filePath(target);
  const lines = msgs.map(m => JSON.stringify({ role: m.role, content: m.content, ts: m.ts || now }));
  fs.writeFileSync(p, lines.join('\n') + (lines.length ? '\n' : ''), { mode: 0o600 });
}
function buildMessagesForApi(cfgName) {
  // 会话内 有 system 优先；否则用全局配置的 system。
  const msgs = loadMessages(cfgName);
  const cfg = configMod.getCurrent();
  const hasSys = msgs.some(m => m.role === 'system');
  const out = msgs.map(m => ({ role: m.role, content: m.content }));
  if (!hasSys && cfg && cfg.system && cfg.system.trim()) {
    out.unshift({ role: 'system', content: cfg.system });
  }
  return out;
}
module.exports = {
  dir, filePath,
  ensure,
  list,
  getCurrentName,
  setCurrent,
  exists,
  drop,
  clear,
  loadMessages,
  appendMessage,
  setSystem,
  buildMessagesForApi,
};
