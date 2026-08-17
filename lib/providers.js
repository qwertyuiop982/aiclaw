'use strict';
// 内置供应商 / OpenAI 兼容端点 清单
// 每条记录：
//   key                内部识别
//   label              显示名
//   baseURL            聊天补全端点（POST）
//   models             可选模型列表
//   thinking           'thinking-type' | 'reasoning-effort' | 'none'
//   thinkingOptions    thinking-type 的可选值
//   effortLevels       reasoning-effort 的可选值
//   modelStrategies    按模型名覆盖思考策略：
//                        { '<modelName>': { type: 'reasoning-effort'|'thinking-type', effortLevels?, thinkingOptions? } }
//   notes              备注
//   custom             为 true 表示需要手工填全部字段
const PROVIDERS = [
  {
    key: 'deepseek',
    label: 'DeepSeek',
    baseURL: 'https://api.deepseek.com/chat/completions',
    models: ['deepseek-chat', 'deepseek-reasoner', 'deepseek-coder', 'deepseek-v4-pro', 'deepseek-v4-flash'],
    thinking: 'thinking-type',
    thinkingOptions: ['enabled', 'disabled'],
    notes: 'thinking.type=enabled 启用推理',
  },
  {
    key: 'openai',
    label: 'OpenAI',
    baseURL: 'https://api.openai.com/v1/chat/completions',
    models: ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-5', 'gpt-5-mini', 'o1', 'o1-mini', 'o3', 'o3-mini', 'o4-mini'],
    thinking: 'reasoning-effort',
    effortLevels: ['none', 'low', 'medium', 'high', 'xhigh', 'max'],
    notes: 'GPT-5.x/o-series 仅用 reasoning_effort，不带 thinking',
  },
  {
    key: 'kimi',
    label: 'Kimi (Moonshot)',
    baseURL: 'https://api.moonshot.cn/v1/chat/completions',
    models: ['moonshot-v1-auto', 'moonshot-v1-128k', 'kimi-k3', 'kimi-k2-0711-preview', 'kimi-latest'],
    thinking: 'thinking-type',
    thinkingOptions: ['enabled'],
    // 按模型名覆盖思考策略：
    //   kimi-k3      -> reasoning-effort (low/high/max)
    //   kimi-k2.x    -> thinking.type enabled（不可关）
    modelStrategies: {
      'kimi-k3': { type: 'reasoning-effort', effortLevels: ['low', 'high', 'max'] },
      'kimi-k2-0711-preview': { type: 'thinking-type', thinkingOptions: ['enabled'] },
      'kimi-latest': { type: 'thinking-type', thinkingOptions: ['enabled'] },
    },
    notes: 'kimi-k3 仅 reasoning_effort；k2.x 仅 thinking.type=enabled',
  },
  {
    key: 'glm',
    label: 'Zhipu GLM',
    baseURL: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
    models: ['glm-4-plus', 'glm-4-flash', 'glm-4-air', 'glm-5.1', 'glm-5.2', 'glm-5.3'],
    thinking: 'thinking-type',
    thinkingOptions: ['enabled', 'disabled'],
    modelStrategies: {
      'glm-5.2': { type: 'reasoning-effort', effortLevels: ['high', 'max'] },
      'glm-5.3': { type: 'reasoning-effort', effortLevels: ['high', 'max'] },
    },
    notes: 'glm-5.2/5.3 仅 reasoning_effort；5.1 用 thinking.type',
  },
  {
    key: 'minimax',
    label: 'MiniMax',
    baseURL: 'https://api.MiniMax.chat/v1/chat/completions',
    models: ['MiniMax-M2.5', 'MiniMax-M2.7', 'MiniMax-M3'],
    thinking: 'thinking-type',
    thinkingOptions: ['enabled', 'disabled'],
    modelStrategies: {
      'MiniMax-M2.5': { type: 'thinking-type', thinkingOptions: ['enabled'] },
      'MiniMax-M2.7': { type: 'thinking-type', thinkingOptions: ['enabled'] },
    },
    notes: 'm2.5/m2.7 不可关；m3 可控',
  },
  {
    key: 'mimo',
    label: 'Xiaomi MiMo',
    baseURL: 'https://api.xiaomimimo.com/v1/chat/completions',
    models: ['mimo-7b-rl', 'mimo-13b-rl'],
    thinking: 'reasoning-effort',
    effortLevels: ['low', 'medium', 'high'],
    notes: 'reasoning_effort: low/medium/high',
  },
  {
    key: 'ollama',
    label: 'Ollama (local)',
    baseURL: 'http://localhost:11434/v1/chat/completions',
    models: [],
    thinking: 'none',
    notes: 'first start `ollama serve`, then fill model name like qwen2.5:7b',
  },
  {
    key: 'custom',
    label: 'Custom',
    baseURL: '',
    models: [],
    thinking: 'thinking-type',
    thinkingOptions: ['enabled', 'disabled'],
    custom: true,
    notes: 'fill baseURL/model/thinking manually',
  },
];
// 根据 model 名获取最终的思考策略：
//   - 有 modelStrategies 匹配的模型优先；否则用 provider 默认
function strategyFor(provider, modelName) {
  if (!provider) return null;
  const ms = provider.modelStrategies || {};
  if (modelName && ms[modelName]) {
    const s = ms[modelName];
    return {
      type: s.type,
      effortLevels: s.effortLevels || provider.effortLevels || [],
      thinkingOptions: s.thinkingOptions || provider.thinkingOptions || [],
      source: 'model:' + modelName,
    };
  }
  return {
    type: provider.thinking || 'none',
    effortLevels: provider.effortLevels || [],
    thinkingOptions: provider.thinkingOptions || [],
    source: 'provider',
  };
}
function find(key) { return PROVIDERS.find(p => p.key === key) || null; }
function list() { return PROVIDERS.slice(); }
module.exports = { PROVIDERS, find, list, strategyFor };
