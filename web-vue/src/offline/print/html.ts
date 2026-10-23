// রসিদ ব্রাউজার/WebView-এর প্রিন্ট দিয়ে: A4 (ডেস্কটপেও), আর ওয়েবে ৫৮/৮০ মিমি মাপের পাতা।
// পাতার ভেতরেই একটা লুকানো অংশ (#lf-print-root) — @media print-এ শুধু সেটা দেখা যায়, তাই ওয়েবের ফন্ট (Hind Siliguri) কাজে লাগে।
// ব্যবহারকারীর লেখা (দোকানের নাম, রোগীর নাম) সবসময় textContent — কখনো HTML নয়।
import { qrMatrix } from '@/sections/health/qr';
import type { Invoice, PrintOptions } from '../types';
import { receiptModel, type ReceiptModel, type ReceiptRow } from './receipt';

const ROOT_ID = 'lf-print-root';
const STYLE_ID = 'lf-print-style';

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

/** QR → SVG (শুধু সংখ্যা দিয়ে বানানো পথ) */
export function qrSvg(text: string, sizePx: number): SVGSVGElement | null {
  const m = qrMatrix(text);
  if (!m) return null;
  const n = m.length + 8;
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${n} ${n}`);
  svg.setAttribute('width', String(sizePx));
  svg.setAttribute('height', String(sizePx));
  svg.setAttribute('shape-rendering', 'crispEdges');
  let d = '';
  m.forEach((row, y) => row.forEach((on, x) => {
    if (on) d += `M${x + 4} ${y + 4}h1v1h-1z`;
  }));
  const bg = document.createElementNS(ns, 'rect');
  bg.setAttribute('width', String(n));
  bg.setAttribute('height', String(n));
  bg.setAttribute('fill', '#fff');
  const p = document.createElementNS(ns, 'path');
  p.setAttribute('d', d);
  p.setAttribute('fill', '#000');
  svg.append(bg, p);
  return svg;
}

function rows(list: ReceiptRow[], cls: string): HTMLElement {
  const box = el('div', cls);
  for (const r of list) {
    const row = el('div', `lfr-row${r.strong ? ' lfr-strong' : ''}`);
    row.append(el('span', '', r.label), el('span', 'lfr-num', r.value));
    box.append(row);
  }
  return box;
}

/** রসিদের DOM (পরীক্ষাতেও ব্যবহার হয়) */
export function receiptElement(m: ReceiptModel, paper: PrintOptions['paper']): HTMLElement {
  const root = el('div', `lfr lfr-${paper === 'A4' ? 'a4' : 'roll'}`);
  if (m.void) root.append(el('div', 'lfr-void', 'বাতিল')); // bn-ok
  const head = el('div', 'lfr-head');
  head.append(el('div', 'lfr-biz', m.bizName));
  if (m.bizAddress) head.append(el('div', 'lfr-sub', m.bizAddress));
  if (m.bizPhone) head.append(el('div', 'lfr-sub', m.bizPhone));
  head.append(el('div', 'lfr-title', m.title));
  root.append(head, rows(m.meta, 'lfr-meta'));

  const lines = el('div', 'lfr-lines');
  for (const l of m.lines) {
    const row = el('div', 'lfr-line');
    const left = el('div', 'lfr-grow');
    left.append(el('div', 'lfr-name', l.name), el('div', 'lfr-detail', l.detail));
    row.append(left, el('div', 'lfr-num', l.amount));
    lines.append(row);
  }
  root.append(lines, rows(m.totals, 'lfr-totals'), rows(m.payments, 'lfr-pay'));
  if (m.void && m.voidReason) root.append(el('div', 'lfr-sub', `${m.voidReason}`));
  const foot = el('div', 'lfr-foot');
  if (m.qrText) {
    const svg = qrSvg(m.qrText, paper === 'A4' ? 110 : 120);
    if (svg) foot.append(svg);
  }
  if (m.verifyText) foot.append(el('div', 'lfr-sub', m.verifyText));
  foot.append(el('div', 'lfr-sub', m.thanks), el('div', 'lfr-brand', 'Lifolo'));
  root.append(foot);
  return root;
}

const CSS = (paper: PrintOptions['paper']) => `
#${ROOT_ID}{display:none}
@media print{
  @page{${paper === 'A4' ? 'size:A4;margin:14mm' : `size:${paper}mm auto;margin:2mm`}}
  html,body{background:#fff!important}
  body>*:not(#${ROOT_ID}){display:none!important}
  #${ROOT_ID}{display:block!important}
}
#${ROOT_ID} .lfr{font-family:"Hind Siliguri","Nirmala UI","Vrinda",sans-serif;color:#000;background:#fff;position:relative;line-height:1.35}
#${ROOT_ID} .lfr-a4{font-size:12pt;max-width:180mm;margin:0 auto}
#${ROOT_ID} .lfr-roll{font-size:${paper === '58' ? '9pt' : '10.5pt'};width:${paper === '58' ? '48mm' : '72mm'}}
#${ROOT_ID} .lfr-head{text-align:center;border-bottom:1px dashed #000;padding-bottom:4px;margin-bottom:4px}
#${ROOT_ID} .lfr-biz{font-weight:700;font-size:1.35em}
#${ROOT_ID} .lfr-title{font-weight:600;margin-top:2px}
#${ROOT_ID} .lfr-sub{font-size:.9em}
#${ROOT_ID} .lfr-row,#${ROOT_ID} .lfr-line{display:flex;justify-content:space-between;gap:8px}
#${ROOT_ID} .lfr-grow{flex:1;min-width:0}
#${ROOT_ID} .lfr-detail{font-size:.85em}
#${ROOT_ID} .lfr-num{white-space:nowrap;text-align:right}
#${ROOT_ID} .lfr-lines{border-top:1px dashed #000;border-bottom:1px dashed #000;padding:4px 0;margin:4px 0}
#${ROOT_ID} .lfr-line{padding:2px 0}
#${ROOT_ID} .lfr-strong{font-weight:700}
#${ROOT_ID} .lfr-pay{border-top:1px dashed #000;margin-top:4px;padding-top:4px}
#${ROOT_ID} .lfr-foot{text-align:center;margin-top:8px;display:flex;flex-direction:column;align-items:center;gap:2px}
#${ROOT_ID} .lfr-brand{font-size:.75em;letter-spacing:.05em}
#${ROOT_ID} .lfr-void{position:absolute;top:35%;left:50%;transform:translate(-50%,-50%) rotate(-18deg);border:4px solid #000;padding:2px 18px;font-size:2.6em;font-weight:700;opacity:.55}
`;

/** ব্রাউজারের প্রিন্ট — প্রিন্টের জানালা বন্ধ হলে শেষ */
export async function printHtml(invoice: Invoice, opts: PrintOptions): Promise<void> {
  document.getElementById(ROOT_ID)?.remove();
  document.getElementById(STYLE_ID)?.remove();
  const style = el('style');
  style.id = STYLE_ID;
  style.textContent = CSS(opts.paper);
  const root = el('div');
  root.id = ROOT_ID;
  root.append(receiptElement(receiptModel(invoice, !!opts.englishDigits), opts.paper));
  document.head.append(style);
  document.body.append(root);
  try {
    await document.fonts?.ready;
  } catch {
    /* ফন্ট ছাড়াই */
  }
  const cleanup = () => {
    root.remove();
    style.remove();
  };
  await new Promise<void>((resolve) => {
    let over = false;
    const done = () => {
      if (over) return;
      over = true;
      window.removeEventListener('afterprint', done);
      cleanup();
      resolve();
    };
    window.addEventListener('afterprint', done);
    window.print();
    // কিছু WebView afterprint দেয় না: print() ফিরে এলে (জানালা বন্ধ) পরিষ্কার
    setTimeout(done, 1000);
  });
}
