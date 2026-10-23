// থার্মাল রসিদ ছবি হিসেবে: WebView2-এর canvas বাংলা যুক্তাক্ষর ঠিকমতো আঁকে (Hind Siliguri, না পেলে Nirmala UI)।
// layout() শুধু মাপ নিয়ে আঁকার তালিকা বানায় (পরীক্ষাযোগ্য); paint() canvas-এ আঁকে।
import { qrMatrix } from '@/sections/health/qr';
import type { ReceiptModel, ReceiptRow } from './receipt';

export type DrawOp =
  | { k: 'text'; x: number; y: number; text: string; size: number; bold?: boolean; align: 'left' | 'center' | 'right'; invert?: boolean }
  | { k: 'rule'; y: number }
  | { k: 'box'; x: number; y: number; w: number; h: number }
  | { k: 'qr'; x: number; y: number; size: number; text: string }
  | { k: 'stamp'; text: string; cx: number; cy: number; size: number };

export type Measure = (text: string, size: number, bold?: boolean) => number;

const FONT = '"Hind Siliguri", "Nirmala UI", "Vrinda", sans-serif';
export const font = (size: number, bold?: boolean) => `${bold ? 700 : 500} ${size}px ${FONT}`;

/** লেখা ভাঙা (শব্দ ধরে; একটা শব্দই বেশি লম্বা হলে অক্ষর ধরে) */
export function wrap(text: string, width: number, size: number, measure: Measure, bold?: boolean): string[] {
  const out: string[] = [];
  for (const para of String(text).split('\n')) {
    let line = '';
    for (const word of para.split(/\s+/).filter(Boolean)) {
      const cand = line ? `${line} ${word}` : word;
      if (measure(cand, size, bold) <= width) {
        line = cand;
        continue;
      }
      if (line) out.push(line);
      if (measure(word, size, bold) <= width) {
        line = word;
        continue;
      }
      // খুব লম্বা শব্দ: অক্ষর ধরে (যুক্তাক্ষর ভাঙা এড়াতে grapheme ধরে)
      line = '';
      const Seg = (Intl as any).Segmenter;
      const seg: string[] = Seg ? [...new Seg('bn', { granularity: 'grapheme' }).segment(word)].map((s: { segment: string }) => s.segment) : [...word];
      for (const g of seg) {
        if (measure(line + g, size, bold) > width && line) {
          out.push(line);
          line = '';
        }
        line += g;
      }
    }
    out.push(line);
  }
  return out;
}

export function layout(m: ReceiptModel, width: number, measure: Measure): { ops: DrawOp[]; height: number } {
  const ops: DrawOp[] = [];
  const base = width >= 500 ? 25 : 21;
  const lh = (s: number) => Math.round(s * 1.42);
  const pad = 4;
  let y = 6;
  const center = (text: string, size: number, bold?: boolean) => {
    for (const l of wrap(text, width - 2 * pad, size, measure, bold)) {
      ops.push({ k: 'text', x: width / 2, y, text: l, size, bold, align: 'center' });
      y += lh(size);
    }
  };
  const rule = () => {
    y += 4;
    ops.push({ k: 'rule', y });
    y += 8;
  };
  const row = (r: ReceiptRow, size = base) => {
    const vw = measure(r.value, size, r.strong);
    const labelLines = wrap(r.label, width - 2 * pad - vw - 12, size, measure, r.strong);
    ops.push({ k: 'text', x: width - pad, y, text: r.value, size, bold: r.strong, align: 'right' });
    for (const l of labelLines) {
      ops.push({ k: 'text', x: pad, y, text: l, size, bold: r.strong, align: 'left' });
      y += lh(size);
    }
  };

  center(m.bizName, Math.round(base * 1.35), true);
  if (m.bizAddress) center(m.bizAddress, base - 3);
  if (m.bizPhone) center(m.bizPhone, base - 3);
  center(m.title, base, true);
  if (m.void) {
    // কালো পটিতে সাদা "বাতিল"
    const s = Math.round(base * 1.4);
    ops.push({ k: 'box', x: pad, y: y + 2, w: width - 2 * pad, h: lh(s) + 4 });
    ops.push({ k: 'text', x: width / 2, y: y + 4, text: m.voidReason ? `${m.voidLabel} — ${m.voidReason}` : m.voidLabel, size: s, bold: true, align: 'center', invert: true });
    y += lh(s) + 10;
  }
  rule();
  for (const r of m.meta) row(r, base - 2);
  rule();
  for (const l of m.lines) {
    const aw = measure(l.amount, base);
    const nameLines = wrap(l.name, width - 2 * pad - aw - 12, base, measure, true);
    ops.push({ k: 'text', x: width - pad, y, text: l.amount, size: base, align: 'right' });
    for (const n of nameLines) {
      ops.push({ k: 'text', x: pad, y, text: n, size: base, bold: true, align: 'left' });
      y += lh(base);
    }
    for (const d of wrap(l.detail, width - 2 * pad - 16, base - 4, measure)) {
      ops.push({ k: 'text', x: pad + 16, y, text: d, size: base - 4, align: 'left' });
      y += lh(base - 4);
    }
    y += 2;
  }
  rule();
  for (const r of m.totals) row(r, r.strong ? base + 3 : base);
  rule();
  for (const r of m.payments) row(r);
  if (m.qrText && qrMatrix(m.qrText)) {
    y += 10;
    const size = Math.min(width - 2 * pad, width >= 500 ? 220 : 180);
    ops.push({ k: 'qr', x: Math.round((width - size) / 2), y, size, text: m.qrText });
    y += size + 6;
  }
  if (m.verifyText) center(m.verifyText, base - 4);
  if (m.offlineVerify) center(m.offlineVerify, base - 2, true);
  y += 4;
  center(m.thanks, base - 2);
  center(m.notVat, base - 5);
  center('Lifolo', base - 6, true);
  const height = y + 8;
  if (m.demo) ops.push({ k: 'stamp', text: m.demoLabel, cx: width / 2, cy: height / 2, size: Math.round(width / 3.2) });
  if (m.void) ops.push({ k: 'stamp', text: m.voidLabel, cx: width / 2, cy: height * 0.4, size: Math.round(width / 3.6) });
  return { ops, height };
}

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;

export function paint(ctx: Ctx, ops: DrawOp[], width: number, height: number) {
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, width, height);
  ctx.textBaseline = 'top';
  for (const o of ops) {
    if (o.k === 'text') {
      ctx.font = font(o.size, o.bold);
      ctx.textAlign = o.align;
      ctx.fillStyle = o.invert ? '#fff' : '#000';
      ctx.fillText(o.text, o.x, o.y);
    } else if (o.k === 'rule') {
      ctx.fillStyle = '#000';
      for (let x = 0; x < width; x += 8) ctx.fillRect(x, o.y, 4, 2);
    } else if (o.k === 'box') {
      ctx.fillStyle = '#000';
      ctx.fillRect(o.x, o.y, o.w, o.h);
    } else if (o.k === 'qr') {
      const m = qrMatrix(o.text);
      if (!m) continue;
      const n = m.length;
      const cell = Math.floor(o.size / (n + 2));
      const off = Math.floor((o.size - cell * n) / 2);
      ctx.fillStyle = '#000';
      m.forEach((row, yy) => row.forEach((on, xx) => on && ctx.fillRect(o.x + off + xx * cell, o.y + off + yy * cell, cell, cell)));
    } else if (o.k === 'stamp') {
      // জলছাপ: বাঁকা, শুধু রেখা (সাদা-কালোতেও লেখা পড়া যায়)
      ctx.save();
      ctx.translate(o.cx, o.cy);
      ctx.rotate(-0.45);
      ctx.font = font(o.size, true);
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.lineWidth = 3;
      ctx.strokeStyle = '#000';
      ctx.strokeText(o.text, 0, 0);
      ctx.restore();
      ctx.textBaseline = 'top';
    }
  }
}

/** রসিদ → RGBA ছবি (WebView/ব্রাউজারে) */
export async function renderReceipt(m: ReceiptModel, width: number): Promise<{ rgba: Uint8ClampedArray; width: number; height: number }> {
  try {
    await Promise.all([document.fonts.load(font(20)), document.fonts.load(font(20, true))]);
  } catch {
    /* ফন্ট না পেলে Nirmala UI */
  }
  const probe = document.createElement('canvas').getContext('2d')!;
  const measure: Measure = (t, s, b) => {
    probe.font = font(s, b);
    return probe.measureText(t).width;
  };
  const { ops, height } = layout(m, width, measure);
  const c = document.createElement('canvas');
  c.width = width;
  c.height = height;
  const ctx = c.getContext('2d')!;
  paint(ctx, ops, width, height);
  return { rgba: ctx.getImageData(0, 0, width, height).data, width, height };
}
