'use strict';
const axios = require('axios');

// web_fetch: fetch a URL and return its content as plain text.
// Args:
//   url:       absolute URL (http/https), required
//   maxBytes:  integer, default 200000, max 5000000
//   start:     byte offset (for range requests / pagination), default 0
//   selector:  optional substring (case-insensitive); if set, only the slice of HTML
//              containing the first match (and ~maxBytes after it) is returned.
//   timeoutMs: integer, default 20000, max 60000
module.exports = {
  name: 'web_fetch',
  description: 'Fetch a URL and return its body as plain text (HTML stripped). Useful after web_search to read the actual page.',
  parameters: {
    type: 'object',
    properties: {
      url: { type: 'string', description: 'absolute http/https URL' },
      maxBytes: { type: 'integer', description: 'max bytes to read', default: 200000 },
      start: { type: 'integer', description: 'byte offset (use with truncated=true to read next chunk)', default: 0 },
      selector: { type: 'string', description: 'optional: substring to locate (e.g. "<article", "<main"). If matched, only the slice around the first occurrence is kept.', default: '' },
      timeoutMs: { type: 'integer', description: 'timeout in ms', default: 20000 },
    },
    required: ['url'],
  },
  async run(args) {
    const url = String(args.url || '').trim();
    if (!url) return { ok: false, error: 'url required' };
    if (!/^https?:\/\//i.test(url)) return { ok: false, error: 'url must start with http:// or https://' };
    const maxBytes = Math.max(1024, Math.min(5000000, args.maxBytes || 200000));
    const start = Math.max(0, args.start || 0);
    const selector = String(args.selector || '').trim();
    const timeoutMs = Math.max(3000, Math.min(60000, args.timeoutMs || 20000));

    const headers = {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Termux) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0 Mobile Safari/537.36',
      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7',
      'Accept-Language': 'en-US,en;q=0.9,zh-CN;q=0.8',
    };
    if (start > 0) headers['Range'] = 'bytes=' + start + '-';

    try {
      const r = await axios.get(url, {
        headers,
        timeout: timeoutMs,
        responseType: 'text',
        transformResponse: x => x,
        validateStatus: s => s >= 200 && s < 400,
        // Don't cap at network level; we slice ourselves so we never OOM on a truncated html.
        // Still cap to ~maxBytes*2 as a sanity guard against truly enormous responses.
        maxContentLength: Math.max(maxBytes * 2, 1024 * 1024),
      });
      const raw = String(r.data || '');
      const contentType = (r.headers && (r.headers['content-type'] || r.headers['Content-Type'])) || '';
      const statusCode = r.status;

      // Truncate to maxBytes (axios's maxContentLength is a soft cap)
      let slice = raw.length > maxBytes ? raw.slice(0, maxBytes) : raw;
      const truncated = raw.length > maxBytes;

      // Apply selector: find first occurrence and keep ~maxBytes after it
      let selected = false;
      if (selector) {
        const idx = slice.toLowerCase().indexOf(selector.toLowerCase());
        if (idx >= 0) {
          slice = slice.slice(idx);
          if (slice.length > maxBytes) { slice = slice.slice(0, maxBytes); }
          selected = true;
        }
      }

      const text = stripTags(slice).replace(/\n{3,}/g, '\n\n').trim();
      return {
        ok: true,
        result: {
          url,
          statusCode,
          contentType: String(contentType).split(';')[0].trim(),
          bytesRead: raw.length,
          truncated,
          selector,
          selected,
          start,
          content: text,
        },
      };
    } catch (e) {
      let detail = e.message;
      if (e.response) {
        detail = (e.response.status || '') + ' ' + (e.response.statusText || '') +
          ' :: ' + JSON.stringify(e.response.data || {}).slice(0, 400);
      }
      return { ok: false, error: 'fetch failed: ' + detail };
    }
  },
};

// Remove HTML/XML tags, decode entities, normalize whitespace.
// Keeps the readable text; discards scripts/styles entirely.
function stripTags(s) {
  let out = String(s || '');
  // drop <script>...</script> and <style>...</style>
  out = out.replace(/<script\b[\s\S]*?<\/script>/gi, ' ');
  out = out.replace(/<style\b[\s\S]*?<\/style>/gi, ' ');
  // drop <noscript>
  out = out.replace(/<noscript\b[\s\S]*?<\/noscript>/gi, ' ');
  // turn <br>, </p>, </div>, </li>, </tr> into newlines
  out = out.replace(/<\s*\/?\s*(br|p|div|li|tr|h[1-6])\b[^>]*>/gi, '\n');
  // strip remaining tags
  out = out.replace(/<\/?[a-zA-Z][^>]*>/g, '');
  // decode a few common entities (build regex pattern from charCodes to avoid any source-quoting issues)
  const DQ = String.fromCharCode(34);
  const AP = String.fromCharCode(39);
  const reDQ = new RegExp('&' + 'quot;', 'g');
  const reApos1 = new RegExp('&#' + '39;', 'g');
  const reApos2 = new RegExp('&' + 'apos;', 'g');
  out = out.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
           .replace(reDQ, DQ).replace(reApos1, AP).replace(reApos2, AP);
  // numeric entities &#1234;
  out = out.replace(/&#(\d+);/g, (m, n) => {
    const code = parseInt(n, 10);
    return (code >= 0 && code <= 0x10ffff) ? String.fromCodePoint(code) : m;
  });
  // collapse whitespace
  out = out.replace(/[ \t\f\v]+/g, ' ').replace(/\n\s*\n+/g, '\n\n');
  return out.trim();
}