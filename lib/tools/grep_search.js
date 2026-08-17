'use strict';
const { execFile } = require('child_process');

// grep_search: run grep -rEn on a directory.
// Args:
//   pattern:  regex string, required
//   path:     absolute dir or file, required
//   glob:     optional, e.g. '*.js' (passed to --include)
//   maxLines: integer, default 200
//   ignoreCase: boolean, default false
module.exports = {
  name: 'grep_search',
  description: 'Search a file/dir with grep -rEn. Returns file:line:content matches.',
  parameters: {
    type: 'object',
    properties: {
      pattern: { type: 'string', description: 'regex pattern' },
      path: { type: 'string', description: 'absolute file or dir' },
      glob: { type: 'string', description: 'optional include glob', default: '' },
      maxLines: { type: 'integer', description: 'cap on matches', default: 200 },
      ignoreCase: { type: 'boolean', description: 'case insensitive', default: false },
    },
    required: ['pattern', 'path'],
  },
  async run(args) {
    const pat = String(args.pattern || '');
    const p = String(args.path || '');
    if (!pat) return { ok: false, error: 'pattern required' };
    if (!p || !p.startsWith('/')) return { ok: false, error: 'path must be absolute' };
    const argv = ['-rEn'];
    if (args.ignoreCase) argv.push('-i');
    if (args.glob) argv.push('--include=' + args.glob);
    argv.push('--', pat, p);
    const maxLines = Math.max(1, Math.min(2000, args.maxLines || 200));
    const r = await new Promise((resolve) => {
      execFile('grep', argv, { timeout: 20000, maxBuffer: 4 * 1024 * 1024 }, (err, stdout, stderr) => {
        resolve({ err, stdout: String(stdout || ''), stderr: String(stderr || '') });
      });
    });
    // exit 1 = no match (grep), treat as ok with empty list
    const allLines = r.stdout.split(/\r?\n/).filter(Boolean);
    const truncated = allLines.length > maxLines;
    const lines = allLines.slice(0, maxLines);
    const matches = lines.map(l => {
      const m = l.match(/^([^:]+):(\d+):(.*)$/);
      if (m) return { file: m[1], line: parseInt(m[2], 10), content: m[3] };
      return { file: '?', line: 0, content: l };
    });
    return {
      ok: !r.err || r.err.code === 1,
      result: {
        pattern: pat, path: p, glob: args.glob || '',
        count: matches.length, truncated,
        matches,
      },
    };
  },
};
