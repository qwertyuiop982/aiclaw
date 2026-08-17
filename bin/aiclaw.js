#!/usr/bin/env node
'use strict';
const { Command } = require('commander');
const chalk = require('chalk');
const configMod = require('../lib/config');
const chatMod = require('../lib/chat');
const modelMod = require('../lib/model');
const utils = require('../lib/utils');
const setup = require('../lib/setup');
const providers = require('../lib/providers');
const session = require('../lib/session');
const log = {
  info: (m) => console.log(chalk.cyan('INFO  ') + m),
  warn: (m) => console.log(chalk.yellow('WARN  ') + m),
  err:  (m) => console.log(chalk.red('ERROR ') + m),
  ok:   (m) => console.log(chalk.green('OK    ') + m),
  dim:  (m) => console.log(chalk.gray('       ' + m)),
};
async function requireConfigured() {
  if (configMod.isConfigured()) return configMod.getCurrent();
  console.log('');
  log.warn('no API config yet.');
  const tty = require('../lib/tty');
  const items = [
    { label: 'launch create-config wizard', value: 'wizard' },
    { label: 'show help (aiclaw config providers)', value: 'help' },
    { label: 'quit', value: 'exit' },
  ];
  const pick = await tty.arrowChoose('select', items, 0);
  if (pick === 'wizard') await setup.runFirstTimeWizard();
  else if (pick === 'help') { console.log('try: aiclaw config create <n> --provider <key> --baseURL ...'); process.exit(1); }
  else process.exit(0);
  return configMod.getCurrent();
}
const prog = new Command();
prog
  .name('aiclaw')
  .description('AI Chat CLI - OpenAI-compatible with sessions')
  .version('0.4.0');
prog.option('-y, --yes', 'skip interactive prompts (reserved)');
// ----- input -----
prog
  .command('input')
  .description('send content; user -> append to current session; system -> update current session system')
  .argument('<role>', "role: 'user' or 'system'")
  .argument('<tokens...>', 'tokens separated by comma, or @absolutePath')
  .option('--model <name>', 'override model for this call only')
  .option('--no-strip', 'disable tag strip / thinking extraction')
  .option('--no-history', 'user role: do not include history (one-shot request)')
  .option('--global-system', 'system role: write to current config global system instead of current session')
  .option('--no-tools', 'disable tool-calling loop for this turn')
  .option('--max-steps <n>', 'cap tool-calling steps (1 to 20)', '6')
  .action(async (role, tokens, opts) => {
    role = String(role || '').toLowerCase();
    if (role !== 'user' && role !== 'system') { log.err("role must be 'user' or 'system'"); process.exit(2); }
    const cfg = await requireConfigured();
    const flat = [];
    for (const t of tokens) for (const part of String(t).split(',')) flat.push(part);
    const items = flat.map(utils.parseInputToken).filter(Boolean);
    if (!items.length) { log.err('no content'); process.exit(2); }
    const content = utils.joinInputs(items);
    if (role === 'system') {
      const tty = require('../lib/tty');
      const prompts = require('../lib/prompts');
      const presetItems = prompts.list().map(p => ({ label: p.label, value: p.key }));
      presetItems.push({ label: '+ use this input directly as system', value: '__here__' });
      presetItems.push({ label: '(cancel)', value: '__cancel__' });
      const pick = await tty.arrowChoose('how to write system', presetItems, 0);
      if (pick === '__cancel__') { log.warn('cancelled'); return; }
      let sys = '';
      if (pick === '__here__') sys = content;
      else sys = (prompts.find(pick) || {}).content || '';
      if (opts.globalSystem) {
        configMod.patchCurrent({ system: sys });
        log.ok('global system written to current config');
      } else {
        session.setSystem(null, sys);
        log.ok('system written to current session [' + session.getCurrentName() + ']');
      }
      return;
    }
    // role === user
    const cur = session.getCurrentName();
    const useCfg = Object.assign({}, cfg);
    if (opts.model) useCfg.model = opts.model;
    if (!useCfg.model) { log.err('current config has no model'); process.exit(2); }
    session.appendMessage(null, { role: 'user', content });
    let messages = opts.history === false
      ? (() => {
          const sysMsg = cfg.system && cfg.system.trim();
          return sysMsg ? [{ role: 'system', content: cfg.system }, { role: 'user', content }] : [{ role: 'user', content }];
        })()
      : session.buildMessagesForApi();

    // Inject tools description into the (first) system message, if enabled
    const toolsMod = require('../lib/tools');
    const loopMod = require('../lib/tools/loop');
    const useTools = opts.tools !== false; // commander turns --no-tools into opts.tools=false
    const maxSteps = Math.max(1, Math.min(20, parseInt(opts.maxSteps, 10) || 6));
    if (useTools) {
      const toolDesc = toolsMod.describeAll();
      const sysIdx = messages.findIndex(m => m.role === 'system');
      if (sysIdx >= 0) {
        messages[sysIdx] = Object.assign({}, messages[sysIdx], { content: (messages[sysIdx].content || '') + '\n\n' + toolDesc });
      } else {
        messages.unshift({ role: 'system', content: toolDesc });
      }
    }

    log.dim('[session: ' + cur + '   history: ' + messages.length + '   tools: ' + (useTools ? 'on' : 'off') + ']');
    process.stdout.write(chalk.gray('thinking...\n'));
    try {
      const onStep = (info) => {
        if (info.calls && info.calls.length) {
          process.stdout.write(chalk.magenta('\n--- tool calls (step ' + info.step + ') ---\n'));
          for (const c of info.calls) {
            process.stdout.write(chalk.magenta('  > ' + c.name + '(' + JSON.stringify(c.arguments) + ')\n'));
          }
          for (const r of info.results) {
            const head = r.ok ? chalk.green('  < ' + r.name + ' ok') : chalk.red('  < ' + r.name + ' err');
            process.stdout.write(head + '\n');
          }
        }
      };
      const r = await loopMod.runAgentLoop(useCfg, messages, { tools: useTools, maxSteps, onStep });
      const finalText = (r && r.finalText) || '';
      const rendered = opts.strip === false ? finalText : utils.renderReply(finalText);
      process.stdout.write('\n' + rendered + '\n');
      // Save the final assistant turn (no tool_call fences) into the session
      if (finalText) {
        const { body } = utils.splitThinking(finalText);
        session.appendMessage(null, { role: 'assistant', content: utils.stripTags(body).trim() });
      }
      log.dim('[steps: ' + r.steps + '   tool calls: ' + r.totalCalls + ']');
    } catch (e) { log.err(e.message); process.exit(1); }
  });

// ----- tool -----
const toolCmd = prog.command('tool').description('tools (debug/manual invocation)');
toolCmd
  .command('list').description('list all registered tools')
  .action(() => {
    const toolsMod = require('../lib/tools');
    console.log(chalk.gray('available tools (' + toolsMod.order.length + '):'));
    for (const n of toolsMod.order) {
      const t = toolsMod.tools[n];
      console.log('  ' + chalk.cyan(n) + chalk.gray(' - ' + (t.description || '')));
    }
  });
toolCmd
  .command('run <name>').description('run a tool directly (bypass model) with JSON args')
  .argument('<json>', 'JSON object string for arguments')
  .option('--cwd <dir>', 'working directory for shell tool')
  .action(async (name, jsonStr, opts) => {
    const toolsMod = require('../lib/tools');
    if (!toolsMod.tools[name]) { log.err('unknown tool: ' + name); process.exit(1); }
    let args;
    try { args = JSON.parse(jsonStr); }
    catch (e) { log.err('invalid JSON args: ' + e.message); process.exit(2); }
    const ctx = { cwd: opts.cwd || process.env.HOME || '/' };
    const r = await toolsMod.runOne(name, args, ctx);
    console.log(JSON.stringify(r, null, 2));
    process.exit(r && r.ok ? 0 : 1);
  });
// ----- config -----
const cfgCmd = prog.command('config').description('multi-config management');
cfgCmd
  .command('create [name]')
  .description('create new config (no args -> wizard)')
  .option('--baseURL <url>').option('--apiKey <key>').option('--model <name>').option('--thinking <v>').option('--reasoning_effort <v>').option('--system <s>').option('--provider <key>')
  .action(async (name, opts) => {
    const hasFullOpts = !!(opts.baseURL && opts.apiKey && opts.model);
    try {
      if (hasFullOpts) {
        const r = configMod.create(name, opts);
        log.ok('created config ' + name);
        console.log(JSON.stringify(r, null, 2));
      } else await setup.runCreateWizard({ name, ...opts });
    } catch (e) { log.err(e.message); process.exit(1); }
  });
cfgCmd
  .command('wizard').description('explicitly launch create-config wizard')
  .action(async () => { try { await setup.runCreateWizard({}); } catch (e) { log.err(e.message); process.exit(1); } });
cfgCmd
  .command('delete <name>').alias('rm').description('delete config')
  .action((name) => { try { const r = configMod.remove(name); log.ok('deleted ' + r.removed + ', current: ' + r.current); } catch (e) { log.err(e.message); process.exit(1); } });
cfgCmd
  .command('use <name>').description('switch active config')
  .action((name) => { try { const r = configMod.use(name); log.ok('current config: ' + r.name + ' (' + r.model + ')'); } catch (e) { log.err(e.message); process.exit(1); } });
cfgCmd
  .command('list').alias('ls').description('list all configs')
  .action(() => {
    const { current, names } = configMod.listNames();
    if (!names.length) { log.warn('no config yet'); console.log('use: aiclaw config wizard'); return; }
    for (const n of names) {
      const c = configMod.get(n);
      const tag = (n === current) ? chalk.bgGreen.black(' * ') + ' ' : '   ';
      console.log(tag + chalk.cyan(n) + chalk.gray('  ' + c.baseURL + '  ' + (c.model || '<no model>')));
    }
  });
cfgCmd
  .command('show [name]').description('show config details (default current)')
  .action((name) => {
    const c = name ? configMod.get(name) : configMod.getCurrent();
    if (!c) { log.err('config not found'); process.exit(1); }
    const safe = Object.assign({}, c, { apiKey: c.apiKey ? '***' + c.apiKey.slice(-4) : '' });
    console.log(JSON.stringify(safe, null, 2));
  });
cfgCmd
  .command('system').description('interactive set global system prompt for current config')
  .action(async () => {
    try { await setup.runSetSystemWizard(); } catch (e) { log.err(e.message); process.exit(1); }
  });
cfgCmd
  .command('providers').description('list built-in providers')
  .action(() => {
    for (const p of providers.list()) {
      console.log(chalk.bold(p.label) + chalk.gray('  [' + p.key + ']'));
      console.log('  ' + chalk.gray('baseURL: ') + (p.baseURL || '(need to fill manually)'));
      if (p.models && p.models.length) console.log('  ' + chalk.gray('models : ') + p.models.join(', '));
      console.log('  ' + chalk.gray('think  : ') + (p.thinking || 'none') +
        (p.thinkingOptions ? ' [' + p.thinkingOptions.join('/') + ']' : '') +
        (p.effortLevels ? ' [' + p.effortLevels.join('/') + ']' : ''));
      if (p.modelStrategies) {
        const ms = Object.keys(p.modelStrategies).map(k => k + ' -> ' + p.modelStrategies[k].type).join('; ');
        console.log('  ' + chalk.gray('overrides: ') + ms);
      }
      if (p.notes) console.log('  ' + chalk.gray('note   : ') + p.notes);
      console.log('');
    }
  });
// ----- model -----
const modelCmd = prog.command('model').description('model management');
modelCmd
  .command('list').description('fetch available models for current config')
  .option('--name <cfgName>')
  .action(async (opts) => {
    let cfg;
    try {
      cfg = await requireConfigured();
      if (opts.name) cfg = configMod.get(opts.name) || cfg;
    } catch (e) { log.err(e.message); process.exit(1); }
    if (!cfg) { log.err('config not found: ' + opts.name); process.exit(1); }
    try {
      const { url, list } = await modelMod.fetchModels(cfg.baseURL, cfg.apiKey);
      console.log(chalk.gray('source: ') + chalk.cyan(url));
      console.log(chalk.gray('config: ') + chalk.cyan(opts.name || cfg.name));
      console.log(chalk.gray('count : ') + chalk.cyan(list.length));
      console.log('');
      for (const m of list) {
        const mark = (m.id === cfg.model) ? chalk.bgGreen.black(' * ') + ' ' : '   ';
        const own = m.owned_by ? chalk.gray('  (' + m.owned_by + ')') : '';
        console.log(mark + chalk.cyan(m.id) + own);
      }
    } catch (e) { log.err(e.message); process.exit(1); }
  });
modelCmd
  .command('set <name>').description('set default model for current config')
  .action(async (name) => {
    if (!name || !String(name).trim()) { log.err('model name required'); process.exit(2); }
    await requireConfigured();
    try {
      const before = configMod.getCurrent();
      log.ok('model set to ' + name + ' (was: ' + (before.model || '<empty>') + ')');
      configMod.patchCurrent({ model: name });
    } catch (e) { log.err(e.message); process.exit(1); }
  });
modelCmd
  .command('show').description('show current config model and thinking strategy')
  .action(async () => {
    const cfg = await requireConfigured();
    console.log(chalk.gray('config : ') + chalk.cyan(cfg.name));
    console.log(chalk.gray('model  : ') + chalk.cyan(cfg.model || '<empty>'));
    console.log(chalk.gray('baseURL: ') + chalk.cyan(cfg.baseURL));
    const ts = chatMod.resolveThinkingStrategy(cfg);
    console.log(chalk.gray('think  : ') + chalk.cyan(ts.mode + (ts.value ? (' = ' + ts.value) : '')));
  });
// ----- session -----
const sesCmd = prog.command('session').description('session management');
sesCmd
  .command('new <name>').description('create new session and switch to it')
  .action((name) => {
    try { session.setCurrent(name); log.ok('current session: ' + name + ' (created)'); }
    catch (e) { log.err(e.message); process.exit(1); }
  });
sesCmd
  .command('use [name]').description('switch session (omit name -> show current)')
  .action((name) => {
    if (!name) {
      console.log('current session: ' + chalk.cyan(session.getCurrentName()));
      return;
    }
    try { session.setCurrent(name); log.ok('switched to session: ' + name); }
    catch (e) { log.err(e.message); process.exit(1); }
  });
sesCmd
  .command('list').alias('ls').description('list all sessions')
  .action(() => {
    const arr = session.list();
    if (!arr.length) { log.warn('no session yet'); return; }
    for (const s of arr) {
      const tag = s.current ? chalk.bgGreen.black(' * ') + ' ' : '   ';
      const last = s.lastTs ? new Date(s.lastTs).toLocaleString() : new Date(s.mtime).toLocaleString();
      console.log(tag + chalk.cyan(s.name) + chalk.gray('   messages ' + s.messages + '   last active ' + last));
    }
  });
sesCmd
  .command('show [name]').description('show messages of session (default current)')
  .action((name) => {
    const n = name || session.getCurrentName();
    const msgs = session.loadMessages(n);
    if (!msgs.length) { console.log(chalk.gray('(empty session)')); return; }
    console.log(chalk.gray('session: ' + n + '   messages: ' + msgs.length));
    msgs.forEach((m, i) => {
      const role = m.role === 'user' ? chalk.green('USER') : m.role === 'assistant' ? chalk.cyan('ASST') : chalk.yellow('SYS');
      console.log('');
      console.log('--- #' + (i + 1) + ' ' + role + ' ---');
      const ct = (m.content || '').length > 800 ? (m.content.slice(0, 800) + '...(total ' + m.content.length + ' chars)') : m.content;
      console.log(ct);
    });
  });
sesCmd
  .command('clear [name]').description('clear session (default current)')
  .action((name) => {
    try { const n = session.clear(name || null); log.ok('cleared: ' + n); }
    catch (e) { log.err(e.message); process.exit(1); }
  });
sesCmd
  .command('drop <name>').alias('rm').description('delete session (auto-create default if current)')
  .action((name) => {
    try { session.drop(name); log.ok('deleted: ' + name); }
    catch (e) { log.err(e.message); process.exit(1); }
  });
// ----- global -----
prog
  .command('init').description('first-time config wizard')
  .action(async () => { await setup.runCreateWizard({}); });
prog
  .command('reset').description('reset ~/.aiclaw/config.json')
  .action(async () => {
    const tty = require('../lib/tty');
    const v = await tty.ask('confirm reset? type YES to continue', '');
    if (v !== 'YES') { log.warn('cancelled'); return; }
    const fs = require('fs');
    fs.writeFileSync(configMod.defaultPath(), JSON.stringify({ current: '', currentSession: '', configs: {} }, null, 2));
    log.ok('reset done');
  });
prog.addHelpText('after', `\nmain commands:\n  aiclaw config providers        list built-in providers\n  aiclaw config list / use / show\n  aiclaw config system            set global system (only used when no session system)\n  aiclaw model list / set / show  fetch models, set default, show current strategy\n  aiclaw input user "Q"            append Q to current session with history\n  aiclaw input user "Q" --no-history  one-shot without history\n  aiclaw input user "Q" --model X     one-shot with overridden model\n  aiclaw input system "..."        update current session system (guided menu)\n  aiclaw input system "..." --global-system  update global system of current config\n\nsessions:\n  aiclaw session list             show all sessions + current\n  aiclaw session new <name>        create and switch\n  aiclaw session use [name]        switch (omit = show current)\n  aiclaw session show [name]       show messages\n  aiclaw session clear [name]      clear\n  aiclaw session drop <name>       delete\n\nstorage:\n  - API/baseURL/apiKey/model saved in ~/.aiclaw/config.json (mode 600)\n  - sessions are <name>.jsonl under ~/.aiclaw/sessions/\n  - default session is "default"\n`);
prog.parseAsync(process.argv).catch(err => {
  log.err(err && err.message ? err.message : String(err));
  process.exit(1);
});

