'use strict';
// 工具注册表 + 调用调度。
// 每个工具定义：
//   { name, description, parameters, run(args, ctx) -> {ok, result|error, meta?} }
// parameters 昧一个简化的 JSON Schema：
//   { type: 'object', properties: { name: { type, description, required?, default?, enum? } }, required: [] }
const fs = require('fs');
const path = require('path');

function loadAll(dir) {
  const tools = {};
  const order = [];
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('.js')) continue;
    if (f === 'index.js') continue;
    // loop.js is the agent loop module, not a tool — skip to avoid circular require
    if (f === 'loop.js') continue;
    const t = require(path.join(dir, f));
    if (!t || !t.name) continue;
    tools[t.name] = t;
    order.push(t.name);
  }
  return { tools, order };
}
const DIR = __dirname;
const { tools, order } = loadAll(DIR);

// 按 schema 做轻量验证 + 类型转换
function coerceType(value, type) {
  if (value === undefined || value === null) return value;
  switch (type) {
    case 'string': return String(value);
    case 'number': {
      const n = Number(value);
      if (Number.isNaN(n)) throw new Error('expected number, got: ' + value);
      return n;
    }
    case 'integer': {
      const n = Number(value);
      if (!Number.isInteger(n)) throw new Error('expected integer, got: ' + value);
      return n;
    }
    case 'boolean': {
      if (typeof value === 'boolean') return value;
      if (value === 'true' || value === '1') return true;
      if (value === 'false' || value === '0') return false;
      throw new Error('expected boolean, got: ' + value);
    }
    default: return value;
  }
}
function validateArgs(tool, args) {
  const schema = tool.parameters || { type: 'object', properties: {}, required: [] };
  const props = schema.properties || {};
  const required = schema.required || [];
  const out = {};
  // 默认值
  for (const k of Object.keys(props)) {
    if (props[k].default !== undefined && (args == null || args[k] === undefined)) {
      out[k] = props[k].default;
    }
  }
  // 强制类型
  if (args && typeof args === 'object') {
    for (const k of Object.keys(args)) {
      if (!props[k]) continue; // 允许额外字段，工具内部可以忽略
      const p = props[k];
      try { out[k] = coerceType(args[k], p.type || 'string'); }
      catch (e) { throw new Error('arg "' + k + '": ' + e.message); }
      if (p.enum && !p.enum.includes(out[k])) {
        throw new Error('arg "' + k + '" must be one of [' + p.enum.join(',') + ']');
      }
    }
  }
  for (const k of required) {
    if (out[k] === undefined || out[k] === '' || out[k] === null) {
      throw new Error('missing required arg: ' + k);
    }
  }
  return out;
}
async function runOne(name, args, ctx) {
  const tool = tools[name];
  if (!tool) return { ok: false, error: 'unknown tool: ' + name };
  let safe;
  try { safe = validateArgs(tool, args); }
  catch (e) { return { ok: false, error: e.message }; }
  try {
    const r = await tool.run(safe, ctx || {});
    if (r && typeof r === 'object') return r;
    return { ok: true, result: r };
  } catch (e) {
    return { ok: false, error: (e && e.message) ? e.message : String(e) };
  }
}
// 生成选工具描述，用于注入 system 提示
function asNativeTools(names) {
  const use = (names && names.length) ? names.filter(n => tools[n]) : order;
  return use.map(n => ({ type: 'function', function: { name: n, description: tools[n].description || '', parameters: tools[n].parameters || { type: 'object', properties: {}, required: [] } } }));
}
function describeAll(names) {
  const use = (names && names.length) ? names.filter(n => tools[n]) : order;
  const lines = ['# Tool calling', 'Native function/tool calling is preferred when supported. Legacy fallback format:', '```tool_call', '{"name":"<tool_name>","arguments":{...}}', '```', 'Available tools:'];
  for (const n of use) { const t = tools[n]; const props = (t.parameters && t.parameters.properties) || {}; const req = (t.parameters && t.parameters.required) || []; const sig = Object.keys(props).map(k => k + (req.includes(k) ? '' : '?') + ':' + (props[k].type || 'any')).join(', '); lines.push('- ' + n + '(' + sig + ') - ' + (t.description || '')); }
  return lines.join('\n');
}
module.exports = { tools, order, describeAll, asNativeTools, runOne, validateArgs };
