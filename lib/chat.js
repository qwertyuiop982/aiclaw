'use strict';
const axios = require('axios');

function safeParse(s) { try { return JSON.parse(s); } catch (_) { return { type: s }; } }
function resolveThinkingStrategy(cfg) {
  if (cfg.thinking) return { mode: 'thinking-type', value: cfg.thinking };
  if (cfg.reasoning_effort) return { mode: 'reasoning-effort', value: cfg.reasoning_effort };
  return { mode: 'none', value: '' };
}
function protocol(cfg) {
  return cfg.protocol || cfg.apiStyle || 'openai';
}
function openaiBody(cfg, messages, extra) {
  const body = { model: cfg.model, messages, stream: false };
  if (extra) Object.assign(body, extra);
  const t = resolveThinkingStrategy(cfg);
  if (t.mode === 'thinking-type') body.thinking = typeof t.value === 'string' && t.value.startsWith('{') ? safeParse(t.value) : { type: t.value };
  if (t.mode === 'reasoning-effort') body.reasoning_effort = t.value;
  return body;
}
function anthropicMessages(messages) {
  let system = '';
  const out = [];
  for (const m of messages || []) {
    if (m.role === 'system') { system += (system ? '\n\n' : '') + String(m.content || ''); continue; }
    out.push({ role: m.role === 'assistant' ? 'assistant' : 'user', content: m.content == null ? '' : m.content });
  }
  return { system, messages: out };
}
function googleContents(messages) {
  const out = [];
  for (const m of messages || []) {
    if (m.role === 'system') continue;
    const role = (m.role === 'assistant' || m.role === 'model') ? 'model' : 'user';
    let parts;
    if (Array.isArray(m.content)) parts = m.content;
    else parts = [{ text: String(m.content == null ? '' : m.content) }];
    out.push({ role, parts });
  }
  return out;
}
function toGoogleTools(tools) {
  return [{ functionDeclarations: (tools || []).map(t => ({ name: t.function ? t.function.name : t.name, description: t.function ? t.function.description : t.description, parameters: t.function ? t.function.parameters : t.parameters })) }];
}
function endpoint(cfg) {
  const p = protocol(cfg);
  if (p === 'google') return String(cfg.baseURL || '').replace('{model}', encodeURIComponent(cfg.model));
  return cfg.baseURL;
}
function buildRequest(cfg, messages, extra) {
  const p = protocol(cfg);
  if (p === 'anthropic') {
    const x = anthropicMessages(messages);
    const body = { model: cfg.model, max_tokens: cfg.max_tokens || 8192, messages: x.messages };
    if (x.system) body.system = x.system;
    if (extra) { if (extra.tools) body.tools = extra.tools.map(t => ({ name: t.function.name, description: t.function.description, input_schema: t.function.parameters })); if (extra.tool_choice) body.tool_choice = extra.tool_choice; }
    const t = resolveThinkingStrategy(cfg); if (t.mode === 'reasoning-effort' && t.value) body.thinking = { type: 'enabled', budget_tokens: Number(cfg.thinking_budget || 4096) };
    return { url: endpoint(cfg), body, headers: { 'x-api-key': cfg.apiKey || '', 'anthropic-version': cfg.anthropicVersion || '2023-06-01' } };
  }
  if (p === 'google') {
    const body = { contents: googleContents(messages) };
    const system = (messages || []).find(m => m.role === 'system'); if (system) body.systemInstruction = { parts: [{ text: String(system.content || '') }] };
    if (extra && extra.tools) body.tools = toGoogleTools(extra.tools);
    const t = resolveThinkingStrategy(cfg); if (t.mode === 'reasoning-effort' && t.value) body.generationConfig = { thinkingConfig: { thinkingBudget: t.value === 'low' ? 1024 : t.value === 'medium' ? 4096 : 8192 } };
    return { url: endpoint(cfg) + (String(endpoint(cfg)).includes('?') ? '&' : '?') + 'key=' + encodeURIComponent(cfg.apiKey || ''), body, headers: {} };
  }
  return { url: endpoint(cfg), body: openaiBody(cfg, messages, extra), headers: { Authorization: 'Bearer ' + (cfg.apiKey || '') } };
}
async function chat(cfg, messages, opts) {
  const req = buildRequest(cfg, messages, opts && opts.extra);
  try {
    const { data } = await axios.post(req.url, req.body, { headers: Object.assign({ 'Content-Type': 'application/json' }, req.headers), timeout: (opts && opts.timeoutMs) || 120000, validateStatus: s => s >= 200 && s < 300 });
    return data;
  } catch (e) { const detail = e.response ? (e.response.status || '') + ' ' + JSON.stringify(e.response.data || {}).slice(0, 800) : e.message; const err = new Error('chat failed (' + (cfg.name || '') + '/' + cfg.model + '): ' + detail); err.code = 'CHAT_FAIL'; err.cause = e; throw err; }
}
function extractContent(data, cfg) {
  const p = protocol(cfg);
  if (p === 'anthropic') { const blocks = data && data.content || []; return { content: blocks.filter(x => x.type === 'text').map(x => x.text).join(''), reasoning: '', toolCalls: blocks.filter(x => x.type === 'tool_use').map(x => ({ id: x.id, name: x.name, arguments: x.input || {}, provider: 'anthropic' })), raw: data, assistant: { role: 'assistant', content: blocks } }; }
  if (p === 'google') { const parts = data && data.candidates && data.candidates[0] && data.candidates[0].content && data.candidates[0].content.parts || []; return { content: parts.filter(x => x.text).map(x => x.text).join(''), reasoning: '', toolCalls: parts.filter(x => x.functionCall).map(x => ({ id: 'google-' + Date.now(), name: x.functionCall.name, arguments: x.functionCall.args || {}, provider: 'google' })), raw: data, assistant: { role: 'model', content: parts } }; }
  const msg = data && data.choices && data.choices[0] && data.choices[0].message || {}; return { content: msg.content || '', reasoning: msg.reasoning_content || msg.reasoning || '', toolCalls: (msg.tool_calls || []).map(x => ({ id: x.id, name: x.function && x.function.name, arguments: safeParse(x.function && x.function.arguments || '{}') })), raw: data, assistant: msg };
}
function buildMessages(cfg, userText) { const msgs = []; if (cfg.system && cfg.system.trim()) msgs.push({ role: 'system', content: cfg.system }); msgs.push({ role: 'user', content: userText }); return msgs; }
module.exports = { protocol, resolveThinkingStrategy, buildRequest, chat, extractContent, buildMessages, toGoogleTools };
