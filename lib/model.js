'use strict';
const axios = require('axios');

// 根据 baseURL 推算可能的 models 端点
//   https://api.deepseek.com/chat/completions  -> origin https://api.deepseek.com
//   https://api.openai.com/v1/chat/completions   -> origin https://api.openai.com
// 通用返回 { origin, candidates[] }
function deriveEndpoints(baseURL) {
  let u;
  try { u = new URL(baseURL); } catch (e) { throw new Error('baseURL 无效: ' + baseURL); }
  const origin = u.origin;
  const pathName = u.pathname.replace(/\/+$/, '');
  // candidates 按优先级
  const cands = [
    origin + '/models',
    origin + '/v1/models',
    origin + pathName.replace(/\/chat\/completions$/, '/models'),
    origin + pathName.replace(/\/chat\/completions$/, '/v1/models'),
  ];
  // 去重保序
  const seen = new Set();
  return cands.filter(x => { if (seen.has(x)) return false; seen.add(x); return true; });
}

async function fetchModels(baseURL, apiKey) {
  const errors = [];
  for (const url of deriveEndpoints(baseURL)) {
    try {
      const { data } = await axios.get(url, {
        headers: { Authorization: 'Bearer ' + (apiKey || '') },
        timeout: 15000,
        validateStatus: s => s >= 200 && s < 300,
      });
      const list = normalizeList(data);
      if (list.length) return { url, list, raw: data };
      errors.push(url + ' -> 空列表');
    } catch (e) {
      errors.push(url + ' -> ' + (e.response ? (e.response.status + ' ' + (e.response.statusText||'')) : e.message));
    }
  }
  const e = new Error('未能从以下端点拉到模型列表:\n' + errors.join('\n'));
  e.code = 'NO_MODELS';
  throw e;
}

// 适配多种响应：{ data: [...] }  或  { models: [...] }  或  [...] 直接数组
function normalizeList(data) {
  let arr = [];
  if (Array.isArray(data)) arr = data;
  else if (data && Array.isArray(data.data)) arr = data.data;
  else if (data && Array.isArray(data.models)) arr = data.models;
  arr = arr.filter(Boolean);
  return arr.map(m => {
    if (typeof m === 'string') return { id: m, owned_by: '' };
    if (m && typeof m === 'object') {
      const id = m.id || m.name || m.model || '';
      const owned_by = m.owned_by || m.owner || '';
      const obj = { id, owned_by };
      if (m.created) obj.created = m.created;
      if (m.type) obj.type = m.type;
      return obj;
    }
    return null;
  }).filter(x => x && x.id);
}

module.exports = { deriveEndpoints, fetchModels, normalizeList };
