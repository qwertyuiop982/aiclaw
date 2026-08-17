'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');

function expandPath(p) {
  if (!p) return p;
  if (p === '~' || p.startsWith('~/')) return path.join(os.homedir(), p.slice(1));
  return path.resolve(p);
}

// read_file: read a text file (utf8).
// Args:
//   path:     file path (absolute, or relative like "./x.txt", or "~/..." which expands to HOME), required
//   maxBytes: integer, default 200000, max 2000000
//   start:    integer byte offset, default 0
module.exports = {
  name: 'read_file',
  description: 'Read a local text file and return its content (utf8, size-limited).',
  parameters: {
    type: 'object',
    properties: {
      path: { type: 'string', description: 'file path (absolute, or relative like "./x.txt", or "~/..." which expands to HOME)' },
      maxBytes: { type: 'integer', description: 'max bytes to read', default: 200000 },
      start: { type: 'integer', description: 'byte offset to start at', default: 0 },
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
    if (!stat.isFile()) return { ok: false, error: 'not a regular file' };
    const max = Math.max(1024, Math.min(2000000, args.maxBytes || 200000));
    const start = Math.max(0, args.start || 0);
    const fd = fs.openSync(p, 'r');
    try {
      const len = Math.min(max, stat.size - start);
      const buf = Buffer.alloc(Math.max(0, len));
      if (len > 0) fs.readSync(fd, buf, 0, len, start);
      return {
        ok: true,
        result: {
          path: p, size: stat.size, start, bytesRead: buf.length,
          content: buf.toString('utf8'),
          truncated: (start + buf.length) < stat.size,
        },
      };
    } finally {
      try { fs.closeSync(fd); } catch (_) {}
    }
  },
};
