// খোঁজার সাহায্য (হাতে চালানো): node e2e/web/explore.mjs <role> <phone|desktop> <path> [path...] — স্ক্রিনশট আর দৃশ্যমান বোতাম/লেখা
import path from 'node:path';
import { Journey, launch, close, settle, vis } from './lib.mjs';
import { BASE } from './stack.mjs';
const [role, vp, ...paths] = process.argv.slice(2);
const OUT = process.env.EX_OUT || '/tmp/e2e-explore';
await launch();
const J = new Journey(99, 'explore', 'x');
const page = await J.as(role, vp);
for (const p of paths) {
  const [url, ...clicks] = p.split('>>');
  if (url) await page.goto(`${BASE}${url}`);
  await settle(page, 500);
  for (const c of clicks) {
    try { await vis(page.getByText(c, { exact: false })).first().click({ timeout: 5000 }); } catch (e) { console.log('CLICK FAIL', c); }
    await settle(page, 400);
  }
  const name = (p.replace(/[^\wঀ-৿]+/g, '_') || 'root').slice(0, 60);
  await page.screenshot({ path: path.join(OUT, `${role}-${name}.png`), fullPage: !!process.env.FULL });
  const info = await page.evaluate(() => {
    const vis = (el) => { const r = el.getBoundingClientRect(); return r.width && r.height; };
    const btn = [...document.querySelectorAll('button,a,[role=button],[role=tab],input,select,textarea')].filter(vis).map((e) => `${e.tagName.toLowerCase()}${e.getAttribute('role') ? '[' + e.getAttribute('role') + ']' : ''}:${(e.getAttribute('aria-label') || e.innerText || e.placeholder || e.name || '').trim().replace(/\s+/g, ' ').slice(0, 50)}`);
    return { url: location.pathname + location.search, text: document.body.innerText.replace(/\n+/g, ' | ').slice(0, 1500), btn };
  });
  console.log(`=== ${p} → ${info.url}\n${info.text}\nCTRL: ${info.btn.join(' ; ')}`);
}
console.log('ERRS', J.errs);
await close();
