// vercel.json পড়ে rewrites/redirects/headers/functions/crons-কে সার্ভারের নিয়মে বদলানো।
// প্রতিবার চালু হওয়ার সময় পড়া হয়, তাই vercel.json আর এই সার্ভার কখনো আলাদা হয় না।
//
// Vercel `source`-এ path-to-regexp (v6) ব্যবহার করে, strict + case-sensitive। এখানে তার যতটুকু
// vercel.json-এ লাগে ততটুকু: `:name`, `:name(regex)`, `(regex)` আর `? * +` মডিফায়ার।
'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULT_SEGMENT = '[^\\/#\\?]+?';
const escapeRe = (s) => s.replace(/[.+*?^${}()[\]|/\\]/g, '\\$&');

/** বন্ধনীর ভেতরের লেখা (নেস্টেড বন্ধনী আর \ এস্কেপ সামলে) */
function readGroup(src, i) {
  let depth = 1;
  let j = i + 1;
  let out = '';
  while (j < src.length) {
    const c = src[j];
    if (c === '\\') {
      out += c + (src[j + 1] || '');
      j += 2;
      continue;
    }
    if (c === '(') depth++;
    if (c === ')') {
      depth--;
      if (depth === 0) return { pattern: out, end: j + 1 };
    }
    out += c;
    j++;
  }
  throw new Error(`Unbalanced pattern in source: ${src}`);
}

/** path-to-regexp v6-এর মতো (strict, sensitive, end) — { re, keys } */
function compileSource(src) {
  const keys = [];
  let re = '';
  let unnamed = 0;
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === '\\') {
      re += escapeRe(src[i + 1] || '');
      i += 2;
      continue;
    }
    let name = null;
    let pattern = null;
    if (c === ':') {
      const m = /^[A-Za-z0-9_]+/.exec(src.slice(i + 1));
      if (!m) throw new Error(`Missing parameter name in source: ${src}`);
      name = m[0];
      i += 1 + name.length;
      if (src[i] === '(') {
        const g = readGroup(src, i);
        pattern = g.pattern;
        i = g.end;
      }
    } else if (c === '(') {
      const g = readGroup(src, i);
      pattern = g.pattern;
      name = String(unnamed++);
      i = g.end;
    } else {
      re += escapeRe(c);
      i++;
      continue;
    }
    pattern = pattern || DEFAULT_SEGMENT;
    keys.push(name);
    const mod = '?*+'.includes(src[i] || '_') ? src[i++] : '';
    if (!mod) {
      re += `(${pattern})`;
      continue;
    }
    // মডিফায়ারের আগের "/" ঐচ্ছিক অংশের ভেতরে যায় (path-to-regexp-এর prefix নিয়ম)
    let prefix = '';
    if (re.endsWith('\\/')) {
      re = re.slice(0, -2);
      prefix = '\\/';
    }
    if (mod === '?') re += `(?:${prefix}(${pattern}))?`;
    else re += `(?:${prefix}((?:${pattern})(?:${prefix}(?:${pattern}))*))${mod === '*' ? '?' : ''}`;
  }
  return { re: new RegExp(`^${re}$`), keys };
}

/** মিল হলে { name: value } (কাঁচা, ডিকোড না করা), না হলে null */
function matchSource(rule, pathname) {
  const m = rule.re.exec(pathname);
  if (!m) return null;
  const params = {};
  rule.keys.forEach((k, idx) => {
    params[k] = m[idx + 1] === undefined ? '' : m[idx + 1];
  });
  return params;
}

function safeDecode(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

/**
 * destination-এ :name আর $1 বসানো। পথের অংশে কাঁচা মান; query-তে ডিকোড করে আবার এনকোড —
 * যাতে মানের ভেতরের & বা = দিয়ে নতুন query প্যারামিটার ঢোকানো না যায়।
 */
function applyDestination(dest, params) {
  const sub = (s, enc) => s.replace(/:([A-Za-z0-9_]+)|\$(\d+)/g, (all, name, num) => {
    const key = name !== undefined ? name : String(Number(num) - 1);
    if (!Object.prototype.hasOwnProperty.call(params, key)) return all;
    return enc ? encodeURIComponent(safeDecode(params[key])) : params[key];
  });
  const q = dest.indexOf('?');
  if (q === -1) return sub(dest, false);
  return sub(dest.slice(0, q), false) + '?' + sub(dest.slice(q + 1), true);
}

/** সাধারণ glob (functions-এর কী, যেমন "api/community.js" বা "api/**\/*.js") */
function globToRe(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') {
      re += '.*';
      i++;
      if (glob[i + 1] === '/') i++;
    } else if (c === '*') re += '[^/]*';
    else re += escapeRe(c);
  }
  return new RegExp(`^${re}$`);
}

const SUPPORTED_RULE_KEYS = new Set(['source', 'destination', 'headers', 'permanent', 'statusCode']);
const IGNORED_TOP_KEYS = new Set(['$schema', 'git', 'ignoreCommand', 'buildCommand', 'installCommand', 'outputDirectory', 'framework', 'regions', 'github']);

function loadVercelConfig(file) {
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  const warnings = [];
  const handled = new Set(['functions', 'crons', 'rewrites', 'redirects', 'headers']);
  for (const k of Object.keys(raw)) {
    if (!handled.has(k) && !IGNORED_TOP_KEYS.has(k)) warnings.push(`vercel.json: "${k}" is not supported by the standalone server`);
  }
  const rules = (list, kind) => (Array.isArray(list) ? list : []).flatMap((r) => {
    const extra = Object.keys(r).filter((k) => !SUPPORTED_RULE_KEYS.has(k));
    if (extra.length) {
      // has/missing ইত্যাদি শর্ত এখানে নেই — চুপচাপ ভুল নিয়ম চালানোর চেয়ে বাদ দেওয়া নিরাপদ
      warnings.push(`vercel.json ${kind} "${r.source}": unsupported keys ${extra.join(', ')} (rule skipped)`);
      return [];
    }
    return [{ ...r, ...compileSource(r.source) }];
  });
  const functions = Object.entries(raw.functions || {}).map(([glob, cfg]) => ({ re: globToRe(glob), maxDuration: Number(cfg && cfg.maxDuration) || null }));
  return {
    rewrites: rules(raw.rewrites, 'rewrite'),
    redirects: rules(raw.redirects, 'redirect'),
    headers: rules(raw.headers, 'header'),
    functions,
    crons: Array.isArray(raw.crons) ? raw.crons : [],
    warnings,
  };
}

/** api/ ফোল্ডারের সব .js — Vercel-এর মতো "_" বা "." দিয়ে শুরু হওয়া ফাইল/ফোল্ডার বাদ */
function scanApi(apiDir, base = apiDir, out = new Map()) {
  for (const ent of fs.readdirSync(apiDir, { withFileTypes: true })) {
    if (ent.name.startsWith('_') || ent.name.startsWith('.')) continue;
    const full = path.join(apiDir, ent.name);
    if (ent.isDirectory()) scanApi(full, base, out);
    else if (ent.isFile() && ent.name.endsWith('.js')) {
      const rel = path.relative(base, full).split(path.sep).join('/');
      const route = '/api/' + rel.replace(/\.js$/, '').replace(/(^|\/)index$/, '');
      out.set(route.replace(/\/$/, '') || '/api', { file: full, rel: 'api/' + rel });
    }
  }
  return out;
}

module.exports = { compileSource, matchSource, applyDestination, globToRe, loadVercelConfig, scanApi };
