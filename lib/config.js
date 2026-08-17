'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
function defaultDir() {
  return path.join(os.homedir(), '.aiclaw');
}
function defaultPath() {
  return path.join(defaultDir(), 'config.json');
}
const ALLOWED_PATCH_KEYS = [
  'baseURL', 'apiKey', 'model',
  'thinking', 'reasoning_effort', 'system',
];
function ensure() {
  const dir = defaultDir();
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const p = defaultPath();
  if (!fs.existsSync(p)) {
    const init = { current: '', currentSession: '', configs: {} };
    fs.writeFileSync(p, JSON.stringify(init, null, 2), { mode: 0o600 });
  }
}
function load() {
  ensure();
  const raw = fs.readFileSync(defaultPath(), 'utf8');
  let obj;
  try { obj = JSON.parse(raw); } catch (e) { throw new Error('config.json parse failed: ' + e.message); }
  if (!obj.configs || typeof obj.configs !== 'object') obj.configs = {};
  if (!('current' in obj)) obj.current = '';
  if (!('currentSession' in obj)) obj.currentSession = '';
  return obj;
}
function save(obj) {
  ensure();
  fs.writeFileSync(defaultPath(), JSON.stringify(obj, null, 2), { mode: 0o600 });
}
function isConfigured() {
  const obj = load();
  return !!(obj.current && obj.configs[obj.current]);
}
function getCurrent() {
  const obj = load();
  if (!obj.current) throw new Error('no active config');
  const c = obj.configs[obj.current];
  if (!c) throw new Error('current config not found: ' + obj.current);
  return { name: obj.current, ...c };
}
function listNames() {
  const obj = load();
  return { current: obj.current || null, names: Object.keys(obj.configs) };
}
function get(name) {
  const obj = load();
  if (!obj.configs[name]) return null;
  return { name, ...obj.configs[name] };
}
function create(name, opts) {
  if (!name) throw new Error('missing config name');
  const obj = load();
  if (obj.configs[name]) throw new Error('config exists: ' + name);
  const cfg = {
    baseURL: opts.baseURL || '',
    apiKey:  opts.apiKey  || '',
    model:   opts.model   || '',
    thinking:          opts.thinking          || '',
    reasoning_effort:  opts.reasoning_effort  || '',
    system:            opts.system            || '',
  };
  if (!cfg.baseURL) throw new Error('baseURL required');
  if (!cfg.apiKey)  throw new Error('apiKey required');
  if (!cfg.model)   throw new Error('model required');
  obj.configs[name] = cfg;
  if (!obj.current) obj.current = name;
  save(obj);
  return { name, ...cfg, current: obj.current };
}
function remove(name) {
  const obj = load();
  if (!obj.configs[name]) throw new Error('config not found: ' + name);
  if (Object.keys(obj.configs).length === 1) throw new Error('keep at least one config');
  delete obj.configs[name];
  if (obj.current === name) {
    obj.current = Object.keys(obj.configs)[0] || '';
  }
  save(obj);
  return { removed: name, current: obj.current };
}
function use(name) {
  const obj = load();
  if (!obj.configs[name]) throw new Error('config not found: ' + name);
  obj.current = name;
  save(obj);
  return { name, ...obj.configs[name] };
}
function patchCurrent(patch) {
  const obj = load();
  if (!obj.current) throw new Error('no current config');
  const cur = obj.configs[obj.current];
  if (!cur) throw new Error('current config data missing');
  for (const k of Object.keys(patch || {})) {
    if (ALLOWED_PATCH_KEYS.includes(k)) cur[k] = patch[k];
  }
  save(obj);
  return { name: obj.current, ...cur };
}
module.exports = {
  ensure, load, save,
  ALLOWED_PATCH_KEYS,
  isConfigured,
  get, getCurrent, listNames,
  create, remove, use,
  patchCurrent,
  defaultPath,
};
