'use strict';
const chalk = require('chalk');
const tty = require('./tty');
const configMod = require('./config');
const providers = require('./providers');
const prompts = require('./prompts');
const log = {
  info: (m) => console.log(chalk.cyan('INFO  ') + m),
  warn: (m) => console.log(chalk.yellow('WARN  ') + m),
  err:  (m) => console.log(chalk.red('ERROR ') + m),
  ok:   (m) => console.log(chalk.green('OK    ') + m),
  dim:  (m) => console.log(chalk.gray('       ' + m)),
};
// 按 provider 与选中的模型名，询问思考控制
async function askThinkingControl(provider, modelName) {
  const strat = providers.strategyFor(provider, modelName);
  if (!strat || strat.type === 'none') {
    log.dim('this provider has no thinking control parameter');
    return { thinking: '', reasoning_effort: '', thinking_methods: '' };
  }
  if (strat.type === 'thinking-type') {
    const opts = (strat.thinkingOptions && strat.thinkingOptions.length) ? strat.thinkingOptions : ['enabled', 'disabled'];
    const items = opts.map(v => ({ label: 'thinking.type = ' + v, value: v }));
    items.push({ label: 'do not specify (use model default)', value: '' });
    const r = await tty.arrowChoose('thinking control ' + chalk.gray('[' + strat.source + ']'), items, 0);
    return { thinking: r, reasoning_effort: '', thinking_methods: r };
  }
  if (strat.type === 'reasoning-effort') {
    const lvls = (strat.effortLevels && strat.effortLevels.length) ? strat.effortLevels : ['low', 'medium', 'high'];
    const items = lvls.map(v => ({ label: 'reasoning_effort = ' + v, value: v }));
    items.push({ label: 'do not specify (use model default)', value: '' });
    const r = await tty.arrowChoose('reasoning_effort ' + chalk.gray('[' + strat.source + ']'), items, 0);
    return { thinking: '', reasoning_effort: r };
  }
  return { thinking: '', reasoning_effort: '', thinking_methods: '' };
}
async function runCreateWizard(opts) {
  log.info('create new API config');
  log.dim('all questions have defaults; press Enter to accept; Ctrl+C to quit.');
  // 1. provider
  const providerItems = providers.list().map(p => ({
    label: p.label, value: p.key,
    hint: p.custom ? 'fully manual' : (p.notes || p.baseURL || ''),
  }));
  const providerKey = opts.providerKey || await tty.arrowChoose('select provider', providerItems);
  const provider = providers.find(providerKey);
  if (!provider) throw new Error('unknown provider: ' + providerKey);
  log.dim('provider: ' + provider.label + (provider.notes ? ' - ' + provider.notes : ''));
  // 2. config name
  const name = opts.name || (await tty.ask('config name (e.g. deepseek, kimi-prod)', provider.key));
  if (!name) throw new Error('config name required');
  // 3. apiKey
  const apiKey = opts.apiKey || (await tty.ask('API key (will not be echoed)', ''));
  if (!apiKey) throw new Error('API key required');
  // 4. endpoint
  let baseURL = opts.baseURL || provider.baseURL;
  if (!provider.custom) {
    baseURL = await tty.ask('API baseURL (empty = provider default)', baseURL || '');
  } else {
    baseURL = await tty.ask('API baseURL', '');
  }
  if (!baseURL) throw new Error('baseURL required');
  // 5. model
  let model = opts.model || '';
  if (!model) {
    if (provider.models && provider.models.length) {
      const modelItems = provider.models.map(m => ({ label: m, value: m }));
      modelItems.push({ label: '+ input a custom model name', value: '__custom__' });
      const m = await tty.arrowChoose('select model', modelItems, 0);
      if (m === '__custom__') model = await tty.ask('model name', '');
      else model = m;
    } else {
      model = await tty.ask('model name', provider.key === 'ollama' ? 'qwen2.5:7b' : '');
    }
  }
  if (!model) throw new Error('model name required');
  // 6. thinking control (model-aware)
  let thinking = opts.thinking || '';
  let reasoning_effort = opts.reasoning_effort || '';
  if (!opts.reasoning_effort && !opts.thinking) {
    const t = await askThinkingControl(provider, model);
    thinking = t.thinking;
    reasoning_effort = t.reasoning_effort;
  }
  // 7. system preset
  let system = opts.system || '';
  if (system === '') {
    const presetItems = prompts.list().map(p => ({ label: p.label, value: p.key }));
    presetItems.push({ label: '+ custom system prompt', value: '__custom__' });
    const pick = await tty.arrowChoose('system preset', presetItems, 0);
    if (pick === '__custom__') {
      system = await tty.ask('custom system prompt (empty = skip)', '');
    } else {
      const p = prompts.find(pick);
      system = (p && p.content) || '';
      log.dim('[preview] ' + (p && p.label) + ' : ' + (system || '(empty)'));
    }
  }
  // 8. write
  const obj = configMod.load();
  if (obj.configs[name]) throw new Error('config exists: ' + name);
  const cfg = {
    baseURL, apiKey, model,
    thinking: thinking || '',
    reasoning_effort: reasoning_effort || '',
    system: system || '',
  };
  obj.configs[name] = cfg;
  if (!obj.current) obj.current = name;
  configMod.save(obj);
  log.ok('saved: ' + name);
  return { name, current: obj.current, ...cfg };
}
async function runFirstTimeWizard() {
  log.warn('no config yet, start setup wizard...');
  const r = await runCreateWizard({});
  console.log('');
  console.log(chalk.bold('hint: ') + 'use `aiclaw config list`, `aiclaw model list`, `aiclaw input user "hi"`');
  return r;
}
async function runSetSystemWizard() {
  const cur = configMod.getCurrent();
  log.info('edit system for current config: ' + cur.name);
  const presetItems = prompts.list().map(p => ({
    label: p.label + (p.content ? ' : ' + (p.content.length > 30 ? p.content.slice(0, 30) + '...' : p.content) : ''),
    value: p.key,
  }));
  presetItems.push({ label: '+ custom', value: '__custom__' });
  presetItems.push({ label: '+ load files (one @path per line)', value: '__files__' });
  const pick = await tty.arrowChoose('select preset', presetItems, 0);
  let system = '';
  if (pick === '__custom__') system = await tty.ask('system prompt', '');
  else if (pick === '__files__') {
    system = await tty.ask('file paths (one per line, empty line to finish)', '');
    const fs = require('fs');
    const paths = system.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
    const contents = [];
    for (const p of paths) {
      const real = p.startsWith('~/') ? require('os').homedir() + p.slice(1) : p;
      try { contents.push(fs.readFileSync(real, 'utf8')); log.ok('read: ' + p); }
      catch (e) { log.warn('skip: ' + p + ' (' + e.message + ')'); }
    }
    system = contents.join('\n\n');
  } else {
    const p = prompts.find(pick);
    system = (p && p.content) || '';
  }
  // 统一走 patchCurrent，避免越过白名单
  configMod.patchCurrent({ system });
  log.ok('system updated');
}
module.exports = { runCreateWizard, runFirstTimeWizard, runSetSystemWizard, askThinkingControl };
