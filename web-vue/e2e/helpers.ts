// e2e সাহায্যকারী: test/pages/*.json-এর পাতা (একই নকল ব্যবহারকারী/তথ্য) খোলা, পাতার ভুল ধরা
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type Page } from '@playwright/test';

const here = path.dirname(fileURLToPath(import.meta.url));
export interface PageDef {
  name: string;
  path: string;
  test?: Record<string, any>;
  click?: string[];
  fill?: [string, string][];
  wait?: number;
  sizes?: string[];
}
export const PARTS = fs.readdirSync(path.join(here, '../test/pages')).filter((f) => f.endsWith('.json')).map((f) => f.replace(/\.json$/, '')).sort();
export const pagesOf = (part: string): PageDef[] => JSON.parse(fs.readFileSync(path.join(here, `../test/pages/${part}.json`), 'utf8'));
export const def = (part: string, name: string) => {
  const p = pagesOf(part).find((x) => x.name === name);
  if (!p) throw new Error(`no page ${part}/${name}`);
  return p;
};

/** নকল লগইন/তথ্য (window.__lfTest) বসিয়ে পাতা খোলা — test/shots.mjs-এর মতো */
export async function open(page: Page, p: PageDef, pathOverride?: string) {
  await page.addInitScript((t: any) => {
    (window as any).__lfTest = t;
    try {
      localStorage.clear();
      localStorage.setItem('lf.lang', (t && t.lang) || 'bn');
      if (t && t.theme) localStorage.setItem('lf.theme', t.theme);
      if (t && t.guestArea) localStorage.setItem('lf.guestArea', JSON.stringify(t.guestArea));
      if (t && t.filterArea) localStorage.setItem('lf.filterArea', JSON.stringify(t.filterArea));
    } catch {}
  }, p.test || {});
  await page.goto(pathOverride ?? p.path, { waitUntil: 'networkidle' });
}

/** পাতার JS ভুল আর কনসোলের ভুল জমা (Firebase-এর নেটওয়ার্ক বাদ) */
export function watchErrors(page: Page) {
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/googleapis|Failed to load resource|firestore|identitytoolkit/i.test(m.text())) errs.push(`console: ${m.text()}`);
  });
  return errs;
}

export async function noSideScroll(page: Page) {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(over, 'পাশে স্ক্রল হয় না').toBeLessThanOrEqual(1);
}
