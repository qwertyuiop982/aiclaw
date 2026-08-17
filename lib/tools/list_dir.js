'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');

// list_dir: list immediate children of a directory.
// Args:
//   path: absolute dir, required. Relative paths (".", "..", "subdir") and "~" are auto-expanded.
//   showHidden: boolean, default false
function expandPath(p) {
  if (!p) return p;
  if (p === '~' || p.startsWith('~/')) return path.join(os.homedir(), p.slice(1));
  return path.resolve(p);
}
module.exports = {
  name: 'list_dir',
  description: 'List immediate children of a directory (name, type, size).',
  parameters: {
    type: 'object',
    properties: {
      path: { type: 'string', description: 'directory path (absolute, or relative like "." / "..", or "~/..." which expands to HOME)' },
      showHidden: { type: 'boolean', description: 'include dotfiles', default: false },
    },
    required: ['path'],
  },
  async run(args) {
    const raw = String(args.path || '');
    if (!raw) return { ok: false, error: 'path required' };
    const p = expandPath(raw);
    let stat;
    try { stat = fs.statSync(p); }
    catch (e) { return { ok: false, error: 'stat failed: ' + e.message }; }
    if (!stat.isDirectory()) return { ok: false, error: 'not a directory' };
    const names = fs.readdirSync(p);
    const items = [];
    for (const n of names) {
      if (!args.showHidden && n.startsWith('.')) continue;
      const full = path.join(p, n);
      let st;
      try { st = fs.statSync(full); } catch (_) { continue; }
      items.push({
        name: n,
        type: st.isDirectory() ? 'dir' : (st.isFile() ? 'file' : 'other'),
        size: st.isFile() ? st.size : null,
        mtime: st.mtimeMs,
      });
    }
    items.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : (a.type === 'dir' ? -1 : 1)));
    return { ok: true, result: { path: p, count: items.length, items } };
  },
};
