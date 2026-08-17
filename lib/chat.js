'use strict';
const axios = require('axios');
// 根据当前模型名解析思考控制策略：
//   - thinking-type：使用 body.thinking = { type: ... } 或 JSON 字符串
//   - reasoning-effort：使用 body.reasoning_effort = 'low'|'medium'|'high'|'xhigh'|'max'
//   - none：不传任何思考控制
// 解析规则：
//   1. provider.thinking 可能是 'thinking-type' | 'reasoning-effort' | 'none'
//   2. provider.modelStrategies 可对个别模型名覆盖：
//        { 'kimi-k3': { type: 'reasoning-effort', effortLevels: ['low','high','max'] } }
function resolveThinkingStrategy(cfg) {
  // cfg 里可选带 providerKey / modelStrategies 元信息，这里只看配置本身的字段
  const t = cfg.thinking;
  const r = cfg.reasoning_effort;
  // 优先级：thinking 非空 > reasoning_effort 非空 > 不传
  if (t && t !== '' && t !== null) {
    return { mode: 'thinking-type', value: t };
  }
  if (r && r !== '') {
    return { mode: 'reasoning-effort', value: r };
  }
  return { mode: 'none', value: '' };
}
function buildBody(cfg, messages, extra) {
  const body = {
    model: cfg.model,
    messages,
    stream: false,
  };
  if (extra && typeof extra === 'object') Object.assign(body, extra);
  const ts = resolveThinkingStrategy(cfg);
  if (ts.mode === 'thinking-type') {
    body.thinking = (typeof ts.value === 'string' && ts.value.startsWith('{'))
      ? safeParse(ts.value)
      : { type: ts.value };
  } else if (ts.mode === 'reasoning-effort') {
    body.reasoning_effort = ts.value;
  }
  return body;
}
function safeParse(s) { try { return JSON.parse(s); } catch (e) { return { type: s }; } }
async function chat(cfg, messages, opts) {
  const body = buildBody(cfg, messages, opts && opts.extra);
  const headers = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + (cfg.apiKey || ''),
  };
  try {
    const { data } = await axios.post(cfg.baseURL, body, {
      headers,
      timeout: (opts && opts.timeoutMs) || 120000,
      validateStatus: s => s >= 200 && s < 300,
    });
    return data;
  } catch (e) {
    let detail = e.message;
    if (e.response) {
      detail = (e.response.status || '') + ' ' + (e.response.statusText || '') +
        ' :: ' + JSON.stringify(e.response.data || {}).slice(0, 800);
    }
    const err = new Error('chat failed (' + (cfg.name || '') + '/' + cfg.model + '): ' + detail);
    err.code = 'CHAT_FAIL';
    err.cause = e;
    throw err;
  }
}
function extractContent(data) {
  const ch = data && data.choices && data.choices[0];
  if (!ch) return { content: '', reasoning: '', raw: data };
  const msg = ch.message || {};
  return {
    content: msg.content || '',
    reasoning: msg.reasoning_content || msg.reasoning || '',
    raw: data,
  };
}
function buildMessages(cfg, userText) {
  const msgs = [];
  if (cfg.system && cfg.system.trim()) msgs.push({ role: 'system', content: cfg.system });
  msgs.push({ role: 'user', content: userText });
  return msgs;
}
module.exports = { resolveThinkingStrategy, buildBody, chat, extractContent, buildMessages };
