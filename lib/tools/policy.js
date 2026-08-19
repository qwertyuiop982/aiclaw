'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const dns = require('dns').promises;

function workspace() {
  const root = process.env.AICLAW_WORKSPACE || process.cwd();
  return fs.realpathSync.native(root);
}
function inside(p) {
  const root = workspace();
  const abs = path.resolve(root, p || '.');
  let real;
  try { real = fs.realpathSync.native(abs); } catch (_) { real = abs; }
  return real === root || real.startsWith(root + path.sep);
}
function safePath(input) {
  if (!input) throw new Error('path required');
  const root = workspace();
  const abs = path.isAbsolute(input) ? path.resolve(input) : path.resolve(root, input);
  if (!inside(abs)) throw new Error('path outside workspace');
  return abs;
}
const DEFAULT_COMMANDS = new Set(['pwd','ls','find','grep','cat','head','tail','wc','git','node','npm','cmake','make','ninja','clang','clang++','qmake','python','python3','echo','printf','sed','sort','du','file']);
function safeCommand(cmd, ctx) {
  const base = path.basename(cmd);
  const allow = ctx && Array.isArray(ctx.allowCmds) ? new Set(ctx.allowCmds.map(String)) : DEFAULT_COMMANDS;
  if (!allow.has(cmd) && !allow.has(base)) throw new Error('cmd not allowed: ' + cmd);
  return cmd;
}
async function publicUrl(url) {
  const u = new URL(url);
  if (!['http:','https:'].includes(u.protocol)) throw new Error('only http/https allowed');
  const host = u.hostname.toLowerCase();
  if (host === 'localhost' || host.endsWith('.localhost') || host === 'metadata.google.internal') throw new Error('local host blocked');
  if (/^(127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.)/.test(host) || host === '::1' || host.startsWith('fc') || host.startsWith('fd')) throw new Error('private address blocked');
  const records = await dns.lookup(host, { all: true });
  for (const r of records) {
    const ip = r.address;
    if (/^(127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.)/.test(ip) || ip === '::1' || ip.startsWith('fc') || ip.startsWith('fd')) throw new Error('private address blocked');
  }
  return u;
}
module.exports = { workspace, inside, safePath, safeCommand, publicUrl };
