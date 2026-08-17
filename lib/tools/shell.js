'use strict';
const { execFile } = require('child_process');
const path = require('path');

function runExec(cmd, args, opts) {
  return new Promise((resolve) => {
    const child = execFile(cmd, args, opts, (err, stdout, stderr) => {
      resolve({ err, stdout: String(stdout || ''), stderr: String(stderr || '') });
    });
    if (opts.timeoutMs) {
      setTimeout(() => { try { child.kill('SIGKILL'); } catch (_) {} }, opts.timeoutMs);
    }
  });
}

// shell: run a single command with arguments. No shell string parsing (avoid injection).
// Args:
//   cmd:     string, required, the executable name (must be on PATH or absolute path)
//   args:    array of strings, default []
//   cwd:     string, default process.cwd() (or $HOME)
//   timeoutMs: integer, default 15000, max 120000
//   env:     object, merged with process.env (only allows whitelisted keys if allowlistEnv set)
module.exports = {
  name: 'shell',
  description: 'Run a local command (no shell parsing). Returns stdout/stderr/exitCode.',
  parameters: {
    type: 'object',
    properties: {
      cmd: { type: 'string', description: 'executable name or absolute path' },
      args: { type: 'string', description: 'JSON array of string args', default: '[]' },
      cwd: { type: 'string', description: 'working dir', default: '' },
      timeoutMs: { type: 'integer', description: 'timeout in ms', default: 15000 },
    },
    required: ['cmd'],
  },
  async run(args, ctx) {
    const cmd = String(args.cmd || '').trim();
    if (!cmd) return { ok: false, error: 'cmd required' };
    let argv = [];
    try { argv = JSON.parse(args.args || '[]'); }
    catch (e) { return { ok: false, error: 'args must be a JSON array string' }; }
    if (!Array.isArray(argv)) return { ok: false, error: 'args must be an array' };
    argv = argv.map(x => String(x));
    const timeoutMs = Math.max(1000, Math.min(120000, args.timeoutMs || 15000));
    const cwd = (args.cwd && String(args.cwd).trim()) || (ctx && ctx.cwd) || process.env.HOME || '/';

    // Optional: enforce an allowlist via context.allowCmds (array of exact cmd names).
    const allow = ctx && Array.isArray(ctx.allowCmds) ? ctx.allowCmds : null;
    if (allow && !allow.includes(cmd) && !allow.includes(path.basename(cmd))) {
      return { ok: false, error: 'cmd not in allowlist: ' + cmd };
    }

    const r = await runExec(cmd, argv, { cwd, timeoutMs, maxBuffer: 4 * 1024 * 1024 });
    const exitCode = r.err && r.err.code != null ? r.err.code : 0;
    return {
      ok: !r.err || exitCode === 0,
      result: {
        cmd, args: argv, cwd, exitCode,
        stdout: r.stdout.slice(0, 20000),
        stderr: r.stderr.slice(0, 5000),
        truncated: { stdout: r.stdout.length > 20000, stderr: r.stderr.length > 5000 },
      },
    };
  },
};
