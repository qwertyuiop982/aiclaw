'use strict';
const readline = require('readline');

// 此版本仅保留稳定的 readline 文本提问 + 编号选择两个原始功能。
// 不依赖 ANSI 转义、不依赖 TTY 原始模式，所有环境 (ssh termux / pipe / 棁端 / IDE) 行为一致。
//
// 如要启用 “箭头键高亮” 实验功能，设置环境变量 AICLAW_ARROWS=1，
// 并且需要终端支持 setRawMode (本地真机终端可用、SSH/管道场景不稳定)。

function isTTY() {
  return !!((process.stdin && process.stdin.isTTY) || process.env.AICLAW_FORCE_TTY === '1');
}

function _newRl() {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: isTTY(),
  });
  rl.on('SIGINT', () => process.exit(130));
  return rl;
}

// 文本提问
function ask(prompt, defaultVal) {
  return new Promise(resolve => {
    const rl = _newRl();
    const label = (defaultVal && defaultVal !== '') ? prompt + ' [' + defaultVal + ']: ' : prompt + ': ';
    let done = false;
    function finish(val) {
      if (done) return; done = true;
      try { rl.close(); } catch (e) {}
      if (val === undefined || val === null) { resolve(defaultVal || ''); return; }
      const s = String(val);
      if (s.trim() === '') { resolve(defaultVal || ''); return; }
      resolve(s.trim());
    }
    try {
      rl.question(label, ans => finish(ans));
    } catch (e) {
      finish(undefined);
      return;
    }
    rl.once('close', () => { if (!done) finish(undefined); });
  });
}

// 编号选择：永远、最随运转
async function choose(label, items) {
  console.log('');
  console.log(label);
  items.forEach((it, i) => {
    const num = '  ' + (i + 1) + '. ';
    const sub = it.hint ? '   (' + it.hint + ')' : '';
    console.log(num + it.label + sub);
  });
  while (true) {
    const ans = await ask('选择序号', String(Math.min(items.length, 1)));
    const idx = parseInt(ans, 10);
    if (!isNaN(idx) && idx >= 1 && idx <= items.length) return items[idx - 1].value;
    console.log('请输入 1 - ' + items.length + ' 之间的数字');
  }
}

// 箭头键版本：只有显式开启才使用；不开启全部走 choose()。
// 设计上：渲染只写新行、不动光标；竁个表项更新用幂等覆盖（不等长补空格）。
async function arrowChoose(label, items, initial) {
  if (process.env.AICLAW_ARROWS !== '1' || !isTTY()) return choose(label, items);
  // 实验性：使用 raw mode
  const idx0 = (typeof initial === 'number' && initial >= 0 && initial < items.length) ? initial : 0;
  let idx = idx0;
  return new Promise(resolve => {
    let done = false;
    let savedRows = 0;
    function cleanup() {
      try { process.stdin.setRawMode(false); } catch (e) {}
      try { process.stdin.removeListener('data', onKey); } catch (e) {}
      process.stdin.pause();
      try { process.stdout.write('\x1b[?25h'); } catch (e) {}
    }
    function finish(v) {
      if (done) return; done = true;
      cleanup();
      if (savedRows > 0) {
        // 移动到菜单下方，退出不再覆盖
        process.stdout.write('\x1b[' + savedRows + 'B\r\n');
        savedRows = 0;
      }
      resolve(v);
    }
    function render() {
      for (let i = 0; i < items.length; i++) {
        process.stdout.write('\r\x1b[2K');
        const marker = (i === idx) ? '▶ ' : '  ';
        process.stdout.write(marker + items[i].label);
        if (items[i].hint) process.stdout.write(' \x1b[2m(' + items[i].hint + ')\x1b[0m');
        process.stdout.write('\n');
      }
      process.stdout.write('\x1b[s');  // 保存位置
      process.stdout.write('\x1b[' + items.length + 'A');  // 上移
      savedRows = items.length;
    }
    function onKey(s) {
      if (s === '\u0003') { finish(process.exit(130)); return; }
      if (s === '\r' || s === '\n') { finish(items[idx].value); return; }
      if (s === '\u001b[A' || s === 'k') { idx = (idx - 1 + items.length) % items.length; render(); return; }
      if (s === '\u001b[B' || s === 'j') { idx = (idx + 1) % items.length; render(); return; }
      if (s === 'q' || s === 'Q') { finish(process.exit(0)); return; }
      if (/^[1-9]$/.test(s)) {
        const n = parseInt(s, 10) - 1;
        if (n >= 0 && n < items.length) { idx = n; render(); }
      }
    }
    try {
      process.stdout.write('\x1b[2J\x1b[H');  // 清屏
    } catch (e) {}
    console.log(label);
    try {
      process.stdin.setRawMode(true);
    } catch (e) {
      // raw mode 不可用，返选编号
      return choose(label, items).then(resolve);
    }
    process.stdin.resume();
    process.stdin.setEncoding('utf8');
    try { process.stdout.write('\x1b[?25l'); } catch (e) {}
    render();
    process.stdin.on('data', onKey);
  });
}

module.exports = { ask, choose, arrowChoose, isTTY };
