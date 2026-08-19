'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');

// 去除模型输出中的"标签符号"：HTML/XML 标签与 Markdown 代码围栏
// 规则：
//   1. 去掉所有 <...> 形式的标签（包括自闭合）
//   2. 保留 ``` 围栏中的代码文本内容（去掉围栏符号本身）
//   3. 折叠多余的空行
function stripTags(text) {
  if (text == null) return '';
  let s = String(text);
  // 移除 HTML/XML 标签
  s = s.replace(/<\/?[a-zA-Z][^>]*>/g, '');
  // 把围栏 ``` 替换为换行保留纯文本
  s = s.replace(/```[a-zA-Z0-9_-]*\n?/g, '\n').replace(/```/g, '');
  // 去除控制字符（保留 \n \t）
  s = s.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
  // 合并多个空行
  s = s.replace(/\n{3,}/g, '\n\n');
  return s.trim();
}

// 将模型回复拆成 (thinking, body) 两段：
//   - 如果存在 <think>...</think> / <thinking>...</thinking> 区段，则提取为 thinking
//   - 其余作为 body；body 再 stripTags 一遍
// 输出时思考过程加 "thinking: " 前缀
function splitThinking(text) {
  const raw = String(text || '');
  const re = /<think>([\s\S]*?)<\/think>|<thinking>([\s\S]*?)<\/thinking>/i;
  const m = raw.match(re);
  if (!m) return { thinking: '', body: stripTags(raw) };
  const thinkContent = (m[1] || m[2] || '').trim();
  const body = raw.replace(re, '').trim();
  return { thinking: thinkContent, body: stripTags(body) };
}

// 渲染最终输出：thinking 行加 "thinking: " 前缀
function renderReply(text) {
  const { thinking, body } = splitThinking(text);
  const lines = [];
  if (thinking) {
    for (const line of thinking.split(/\r?\n/)) lines.push('thinking: ' + line);
  }
  if (body) {
    if (lines.length) lines.push('');
    lines.push(body);
  }
  return lines.join('\n');
}

// 解析一段 input 参数：
//   "hello"            -> {type:'text', value:'hello'}
//   @/abs/path.txt     -> {type:'file',  value:'/abs/path.txt'}
//   @~/notes.md        -> {type:'file',  value: ~/notes.md 展开后的绝对路径}
function parseInputToken(tok) {
  if (typeof tok !== 'string') return null;
  const t = tok.trim();
  if (!t) return null;
  if (t.startsWith('@')) {
    let p = t.slice(1);
    if (p.startsWith('~/') || p === '~') p = path.join(os.homedir(), p.slice(1));
    p = path.resolve(p);
    return { type: 'file', value: p };
  }
  // 去掉包裹引号
  let v = t;
  if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
    v = v.slice(1, -1);
  }
  return { type: 'text', value: v };
}

// 读文件内容（UTF-8），附带错误处理
function readFileSafe(p) {
  try {
    return fs.readFileSync(p, 'utf8');
  } catch (e) {
    throw new Error('读取文件失败: ' + p + ' (' + e.message + ')');
  }
}

// 把多段 token 合并为最终 message content（用 \n\n 分隔）
// Interactive input: replace whitespace-delimited @/absolute/path tokens with file contents.
function expandInteractiveFiles(text) {
  const raw = String(text || '');
  return raw.replace(/(^|\s)@(\/[^\s]+)/g, (all, prefix, file) => {
    try { return prefix + readFileSafe(file); }
    catch (e) { throw e; }
  });
}
function joinInputs(items) {
  const parts = [];
  for (const it of items) {
    if (it.type === 'text') parts.push(it.value);
    else if (it.type === 'file') parts.push(readFileSafe(it.value));
  }
  return parts.join('\n\n');
}

module.exports = {
  stripTags,
  splitThinking,
  renderReply,
  parseInputToken,
  readFileSafe,
  joinInputs,
};
