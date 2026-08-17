'use strict';
const axios = require('axios');

function stripTags(s) {
  return String(s || '').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
}
function decodeEntities(s) {
  return String(s || '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/"/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ');
}

// Parse Bing HTML: <li class="b_algo"> ... <div class="b_algoheader"><a href="URL"><h2>TITLE</h2></a></div> ... <p class="b_lineclamp*">snippet</p>
function parseBing(html, limit) {
  const out = [];
  const liRe = /<li[^>]*class="[^"]*b_algo[^"]*"[^>]*>([\s\S]*?)<\/li>/gi;
  let m;
  while ((m = liRe.exec(html)) && out.length < limit) {
    const block = m[1];
    // URL: prefer the header link, fall back to first href inside block
    let url = '';
    const header = block.match(/<div[^>]*class="[^"]*b_algoheader[^"]*"[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>/i);
    if (header) url = header[1];
    if (!url) {
      const any = block.match(/<a[^>]+href="([^"]+)"[^>]*>/i);
      if (any) url = any[1];
    }
    if (!url) continue;
    if (url.startsWith('//')) url = 'https:' + url;
    else if (url.startsWith('/')) url = 'https://www.bing.com' + url;
    if (!/^https?:\/\//i.test(url)) continue;
    // Title: first <h2>...</h2>
    const tm = block.match(/<h2[^>]*>([\s\S]*?)<\/h2>/i);
    const title = tm ? decodeEntities(stripTags(tm[1])) : '';
    if (!title) continue;
    // Snippet: prefer b_lineclamp*, else first <p>
    let snippet = '';
    const lineclamp = block.match(/<p[^>]*class="[^"]*b_lineclamp[^"]*"[^>]*>([\s\S]*?)<\/p>/i);
    if (lineclamp) snippet = decodeEntities(stripTags(lineclamp[1]));
    if (!snippet) {
      const pm = block.match(/<p[^>]*>([\s\S]*?)<\/p>/i);
      if (pm) snippet = decodeEntities(stripTags(pm[1]));
    }
    out.push({ title, url, snippet: snippet.slice(0, 400) });
  }
  return out;
}

module.exports = {
  name: 'web_search',
  description: 'Search Bing (HTML scrape) and return top results (title/url/snippet).',
  parameters: {
    type: 'object',
    properties: {
      query: { type: 'string', description: 'search query' },
      limit: { type: 'integer', description: 'max results (1-20)', default: 8 },
    },
    required: ['query'],
  },
  async run(args) {
    const q = String(args.query || '').trim();
    if (!q) return { ok: false, error: 'query required' };
    const limit = Math.max(1, Math.min(20, args.limit || 8));
    const url = 'https://www.bing.com/search?q=' + encodeURIComponent(q);
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Termux) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0 Mobile Safari/537.36',
      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'Accept-Language': 'en-US,en;q=0.9,zh-CN;q=0.8',
    };
    try {
      const r = await axios.get(url, {
        headers, timeout: 20000, responseType: 'text',
        transformResponse: x => x, validateStatus: s => s >= 200 && s < 400,
      });
      const results = parseBing(r.data, limit);
      if (!results.length) return { ok: false, error: 'no results parsed from bing', meta: { url } };
      return { ok: true, result: { query: q, source: url, count: results.length, results } };
    } catch (e) {
      return { ok: false, error: 'bing fetch failed: ' + e.message };
    }
  },
};