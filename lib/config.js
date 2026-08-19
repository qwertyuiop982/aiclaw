'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
function defaultDir() { return path.join(os.homedir(), '.aiclaw'); }
function defaultPath() { return path.join(defaultDir(), 'config.json'); }
function secretsPath() { return path.join(defaultDir(), 'secrets.json'); }
function readSecrets() { try { return JSON.parse(fs.readFileSync(secretsPath(), 'utf8')); } catch (_) { return {}; } }
function writeSecrets(x) { const tmp = secretsPath() + '.tmp-' + process.pid; fs.writeFileSync(tmp, JSON.stringify(x, null, 2), { mode: 0o600 }); try { fs.chmodSync(tmp, 0o600); } catch (_) {} fs.renameSync(tmp, secretsPath()); }
function hydrate(o) { const sec = readSecrets(); for (const [n, c] of Object.entries(o.configs || {})) if (!c.apiKey && c.apiKeyRef && sec[c.apiKeyRef]) c.apiKey = sec[c.apiKeyRef]; return o; }
function ensure() { const d = defaultDir(); if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true, mode: 0o700 }); const p = defaultPath(); if (!fs.existsSync(p)) fs.writeFileSync(p, JSON.stringify({ current: '', currentSession: '', configs: {} }, null, 2), { mode: 0o600 }); try { fs.chmodSync(p, 0o600); } catch (_) {} }
function load() { ensure(); let o; try { o = JSON.parse(fs.readFileSync(defaultPath(), 'utf8')); } catch (e) { throw new Error('config.json parse failed: ' + e.message); } if (!o.configs || typeof o.configs !== 'object') o.configs = {}; if (!('current' in o)) o.current = ''; if (!('currentSession' in o)) o.currentSession = ''; return hydrate(o); }
function save(o) { ensure(); const secrets = readSecrets(); const clean = JSON.parse(JSON.stringify(o)); for (const [n, c] of Object.entries(clean.configs || {})) { if (c.apiKey) { const ref = c.apiKeyRef || n; secrets[ref] = c.apiKey; c.apiKeyRef = ref; delete c.apiKey; } } writeSecrets(secrets); const p = defaultPath(); const tmp = p + '.tmp-' + process.pid; fs.writeFileSync(tmp, JSON.stringify(clean, null, 2), { mode: 0o600 }); try { fs.chmodSync(tmp, 0o600); } catch (_) {} fs.renameSync(tmp, p); }
function maskSecret(v) { const s = String(v || ''); return s ? (s.length <= 8 ? '***' : s.slice(0, 3) + '***' + s.slice(-4)) : ''; }
function publicConfig(c) { const x = Object.assign({}, c || {}); if ('apiKey' in x) x.apiKey = maskSecret(x.apiKey); return x; }
function isConfigured() { const o = load(); return !!(o.current && o.configs[o.current]); }
function getCurrent() { const o = load(); if (!o.current || !o.configs[o.current]) throw new Error('no active config'); return { name: o.current, ...o.configs[o.current] }; }
function listNames() { const o = load(); return { current: o.current || null, names: Object.keys(o.configs) }; }
function get(name) { const o = load(); return o.configs[name] ? { name, ...o.configs[name] } : null; }
function create(name, opts) { if (!name) throw new Error('missing config name'); const o = load(); if (o.configs[name]) throw new Error('config exists: ' + name); const c = normalize(opts); if (!c.baseURL || !c.apiKey || !c.model) throw new Error('baseURL, apiKey and model are required'); o.configs[name] = c; if (!o.current) o.current = name; save(o); return { name, ...c, current: o.current }; }
function normalize(opts) { return { protocol: opts.protocol || opts.apiStyle || 'openai', baseURL: opts.baseURL || '', apiKey: opts.apiKey || '', model: opts.model || '', thinking: opts.thinking || '', reasoning_effort: opts.reasoning_effort || '', thinking_methods: opts.thinking_methods || '', system: opts.system || '', max_tokens: opts.max_tokens || 8192 }; }
function remove(name) { const o = load(); if (!o.configs[name]) throw new Error('config not found: ' + name); if (Object.keys(o.configs).length === 1) throw new Error('keep at least one config'); delete o.configs[name]; if (o.current === name) o.current = Object.keys(o.configs)[0] || ''; save(o); return { removed: name, current: o.current }; }
function use(name) { const o = load(); if (!o.configs[name]) throw new Error('config not found: ' + name); o.current = name; save(o); return { name, ...o.configs[name] }; }
const ALLOWED_PATCH_KEYS = ['protocol', 'baseURL', 'apiKey', 'model', 'thinking', 'reasoning_effort', 'thinking_methods', 'system', 'max_tokens'];
function patchCurrent(p) { const o = load(); if (!o.current || !o.configs[o.current]) throw new Error('no current config'); for (const k of Object.keys(p || {})) if (ALLOWED_PATCH_KEYS.includes(k)) o.configs[o.current][k] = p[k]; save(o); return { name: o.current, ...o.configs[o.current] }; }
module.exports = { ensure, load, save, defaultPath, secretsPath, ALLOWED_PATCH_KEYS, isConfigured, getCurrent, listNames, get, create, remove, use, patchCurrent, maskSecret, publicConfig };
