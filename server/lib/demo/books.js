// ডেমো: "ডেমো লাইফ ফার্মেসি"-র হিসাব-খাতা — পণ্য (দাম, পরিমাণ, মেয়াদ), মাল কেনা, ৩৫ দিনের কাউন্টার/প্রেসক্রিপশন/অনলাইন বিক্রি,
// নষ্ট আর গণনা, দিনের যোগফল — যাতে "হিসাব" পাতায় আসল দোকানের মতো সংখ্যা দেখা যায়।
// সেবা ভাগের পরে চলে (সেখানে দোকান আর স্টকের ২৪টা নাম লেখা হয়); এই ২৪টা নামই পণ্য, সাথে ফুরিয়ে যাওয়া ২টা (স্টকের তালিকায় নেই)।
// সব সংখ্যা স্থির (এলোমেলো নয়, একই বীজ) — গাইডে লেখা "আজ / ৭ দিন / ৩০ দিন"-এর সংখ্যা প্রতিবার একই।
const B = require('../shopbooks');

const DAY = 24 * 60 * 60 * 1000;

// [নাম, জেনেরিক, ধরন, একক, কেনা দাম, বিক্রি দাম, ছাড়, শেষে কয়টা, কম হলে (lowAt), মেয়াদ (মাস পরে | 'soon5' | 'soon25'), দিনে কতটা বিক্রি হয় (ভার), এক বিক্রিতে কয়টা]
const ITEMS = [
  ['Napa', 'Paracetamol 500 mg', 'Tablet', 'piece', 0.9, 1.2, null, 640, 200, 14, 10, [10, 20]],
  ['Ace', 'Paracetamol 500 mg', 'Tablet', 'piece', 0.95, 1.2, null, 380, 100, 18, 6, [10, 20]],
  ['Seclo', 'Omeprazole 20 mg', 'Capsule', 'piece', 5.2, 7, { type: 'pct', v: 10 }, 260, 60, 20, 7, [10, 14]],
  ['Losectil', 'Omeprazole 20 mg', 'Capsule', 'piece', 4.8, 6, null, 120, 40, 16, 3, [10, 14]],
  ['Maxpro', 'Esomeprazole 20 mg', 'Tablet', 'piece', 6.2, 8, { type: 'pct', v: 10 }, 150, 40, 22, 5, [10, 14]],
  ['Sergel', 'Esomeprazole 20 mg', 'Capsule', 'piece', 5.5, 7, { type: 'pct', v: 5 }, 140, 40, 15, 4, [10, 14]],
  ['Pantonix', 'Pantoprazole 20 mg', 'Tablet', 'piece', 5, 7, null, 90, 30, 19, 2, [10, 14]],
  ['Fexo', 'Fexofenadine 120 mg', 'Tablet', 'piece', 7, 9, { type: 'amt', v: 0.5 }, 110, 30, 17, 3, [7, 10]],
  ['Alatrol', 'Cetirizine 10 mg', 'Tablet', 'piece', 2.5, 3.5, null, 200, 50, 21, 4, [10, 10]],
  ['Tofen', 'Ketotifen 1 mg', 'Tablet', 'piece', 2.6, 3.5, null, 60, 20, 13, 0, [10, 10]],
  ['Montene', 'Montelukast 10 mg', 'Tablet', 'piece', 13, 16, { type: 'pct', v: 10 }, 8, 15, 12, 3, [10, 15]],
  ['Monas', 'Montelukast 10 mg', 'Tablet', 'piece', 13.5, 17, null, 70, 20, 11, 2, [10, 15]],
  ['Ceevit', 'Vitamin C 250 mg', 'Tablet', 'piece', 1.2, 1.5, null, 150, 50, 'soon5', 3, [10, 30]],
  ['Calbo', 'Calcium 500 mg', 'Tablet', 'piece', 8, 10.5, { type: 'pct', v: 10 }, 120, 30, 24, 3, [15, 30]],
  ['Xinc', 'Zinc 20 mg', 'Tablet', 'piece', 2.5, 3, null, 90, 20, 10, 0, [10, 10]],
  ['Comet', 'Metformin 500 mg', 'Tablet', 'piece', 3.6, 5, { type: 'pct', v: 10 }, 300, 100, 20, 6, [20, 60]],
  ['Rosuva', 'Rosuvastatin 10 mg', 'Tablet', 'piece', 14, 18, { type: 'pct', v: 10 }, 6, 20, 15, 3, [10, 30]],
  ['Ecosprin', 'Aspirin 75 mg', 'Tablet', 'piece', 0.5, 0.8, null, 280, 60, 23, 4, [28, 30]],
  ['Bislol', 'Bisoprolol 2.5 mg', 'Tablet', 'piece', 6, 8, { type: 'pct', v: 5 }, 90, 30, 17, 2, [10, 30]],
  ['Amdocal', 'Amlodipine 5 mg', 'Tablet', 'piece', 3.8, 5, { type: 'pct', v: 10 }, 160, 40, 19, 4, [10, 30]],
  ['Azithrocin', 'Azithromycin 500 mg', 'Tablet', 'piece', 30, 40, { type: 'amt', v: 2 }, 24, 10, 'soon25', 2, [3, 5]],
  ['Cef-3', 'Cefixime 200 mg', 'Capsule', 'piece', 32, 40, null, 4, 10, 14, 2, [6, 10]],
  // দাম বেড়েছে, দোকান বিক্রি দাম (MRP) বদলায়নি — প্রতিটা বিক্রিতে ক্ষতি
  ['Flagyl', 'Metronidazole 400 mg', 'Tablet', 'piece', 2.4, 2, null, 130, 40, 18, 3, [10, 21]],
  ['Orsaline-N', 'Oral rehydration salt', 'Sachet', 'sachet', 4.2, 5, null, 180, 50, 26, 5, [5, 10]],
  // ফুরিয়ে গেছে (স্টকের তালিকায় নেই)
  ['Nexum 20', 'Esomeprazole 20 mg', 'Tablet', 'piece', 6.5, 8, null, 0, 20, 12, 0, [10, 10]],
  ['Thyrox 50', 'Levothyroxine 50 mcg', 'Tablet', 'piece', 1.8, 2.5, null, 0, 30, 12, 0, [30, 30]],
];

/** স্থির এলোমেলো (mulberry32) */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** "এখন থেকে n দিন পরের মাস" → "YYYY-MM" (ঢাকা) */
const monthAt = (ms) => new Date(ms + 6 * 3600 * 1000).toISOString().slice(0, 7);

const COUNTER = ['', '', '', 'ডেমো ক্রেতা রাকিব', '', 'ডেমো ক্রেতা মিতু', ''];

/** দিনের যোগফলে যোগ (shopbooks.js-এর dayAdd-এর মতো, শুধু মেমোরিতে) */
function addInto(o, add) {
  for (const [k, v] of Object.entries(add)) {
    if (v && typeof v === 'object') addInto(o[k] || (o[k] = {}), v);
    else if (typeof v === 'number') o[k] = Math.round(((o[k] || 0) + v) * 100) / 100;
    else o[k] = v;
  }
}

module.exports = {
  ITEMS,
  async seed(ctx) {
    const PH = ctx.u.pharmacy.uid;
    const now = ctx.now;
    const today = B.dayStart(now);
    const rand = rng(1501);
    const pick = (list) => list[Math.floor(rand() * list.length)];
    const between = ([a, b]) => a + Math.floor(rand() * (b - a + 1));

    // পণ্য (মেমোরিতে — বিক্রি আর কেনা থেকে শুরুর পরিমাণ হিসাব করে শেষে লেখা)
    const items = ITEMS.map(([name, generic, form, unit, cost, price, disc, finalQty, lowAt, exp, weight, per]) => ({
      id: B.idOf(name), name, generic, form, unit, cost, price, disc, finalQty, lowAt, weight, per,
      expiry: exp === 'soon5' ? monthAt(now + 5 * DAY) : exp === 'soon25' ? monthAt(now + 25 * DAY) : monthAt(now + exp * 30 * DAY),
      events: [], lastSaleAt: null,
    }));
    const byName = new Map(items.map((i) => [i.name, i]));
    const pool = items.flatMap((i) => Array(i.weight).fill(i));
    const days = new Map();
    const day = (k) => days.get(k) || (days.set(k, { day: k }), days.get(k));

    // ---------- বিক্রি: ৩৫ দিন (আজ সহ) ----------
    let n = 0;
    for (let k = 34; k >= 0; k--) {
      const ds = today - k * DAY;
      const counter = 5 + ((k * 7) % 5); // ৫–৯টা কাউন্টারে
      const rxN = k % 3 === 0 ? 2 : 1;
      const online = k % 4 === 1 ? 1 : 0;
      const total = counter + rxN + online;
      for (let j = 0; j < total; j++) {
        // আজকের বিক্রি এখনকার আগে, অন্য দিন সকাল ৯টা থেকে রাত ৯টা
        const at = k === 0 ? ds + Math.floor(((now - ds) * (j + 1)) / (total + 1)) : ds + 9 * 3600 * 1000 + Math.floor((12 * 3600 * 1000 * j) / total);
        const source = j < counter ? 'counter' : j < counter + rxN ? 'rx' : 'online';
        const nLines = source === 'counter' ? 1 + Math.floor(rand() * 3) : 2 + Math.floor(rand() * 2);
        const chosen = [];
        for (let x = 0; x < nLines; x++) {
          const it = pick(pool);
          if (!chosen.includes(it)) chosen.push(it);
        }
        const lines = chosen.map((it) => {
          // কাউন্টারে মাঝে মাঝে দোকানদার নিজে দাম কমায় (ছাড় না দিয়ে পুরো দাম, বা পণ্যের ছাড়)
          const unitDisc = source === 'counter' && rand() < 0.3 ? 0 : B.unitDiscOf(it.price, it.disc);
          return { itemId: it.id, name: it.name, qty: between(it.per), unitPrice: it.price, unitDisc, unitCost: it.cost };
        });
        // বড় বিলে ৫ টাকা কম রাখা (ছোট বিলে নয় — নইলে কেনা দামের নিচে চলে যেত)
        const big = B.priceSale(lines, null).totals.net >= 150;
        const bill = source === 'online' ? { type: 'pct', v: 5 } : source === 'counter' && j % 4 === 3 && big ? { type: 'amt', v: 5 } : null;
        const priced = B.priceSale(lines, bill);
        const sale = {
          at, day: B.dayKey(at), source, lines: priced.lines, totals: priced.totals, billDisc: bill,
          // রোগীর নাম নয় (আসল বিক্রির মতো) — শুধু প্রেসক্রিপশনের কোড।
          // আগে এখানে নাম বাছতে একটা rand() লাগত — সেটা রেখে দেওয়া, যাতে ডেমোর বাকি সংখ্যা (আর টেস্ট) আগের মতো থাকে
          ...(source !== 'counter' && rand() >= 0 ? { rx: { code: `DEMO${String(n + 1).padStart(6, '0')}`, at } } : {}),
          customer: source === 'counter' ? pick(COUNTER) : '', createdAt: at,
        };
        for (const l of priced.lines) {
          const it = byName.get(l.name);
          it.events.push([at, -l.qty]);
          it.lastSaleAt = Math.max(it.lastSaleAt || 0, at);
        }
        addInto(day(sale.day), B.saleDayDelta(sale));
        await ctx.set(`shopBooks/${PH}/sales/demo-sale-${String(++n).padStart(4, '0')}`, sale);
      }
    }

    // ---------- মাল কেনা ----------
    const purchases = [
      [28, 'ডেমো রেনাটা ডিপো', [['Comet', 300], ['Seclo', 200], ['Maxpro', 150], ['Calbo', 120]]],
      [21, 'ডেমো স্কয়ার ডিস্ট্রিবিউটর', [['Napa', 500], ['Ace', 300], ['Fexo', 100], ['Ecosprin', 300]]],
      [14, 'ডেমো বেক্সিমকো ডিপো', [['Montene', 60], ['Rosuva', 90], ['Bislol', 60], ['Amdocal', 120], ['Flagyl', 200]]],
      [6, 'ডেমো রেনাটা ডিপো', [['Seclo', 140], ['Sergel', 100], ['Orsaline-N', 150], ['Azithrocin', 30]]],
      [2, 'ডেমো স্কয়ার ডিস্ট্রিবিউটর', [['Napa', 400], ['Alatrol', 100], ['Cef-3', 20]]],
    ];
    let p = 0;
    for (const [k, supplier, list] of purchases) {
      const at = today - k * DAY + 11 * 3600 * 1000;
      const lines = list.map(([name, qty]) => {
        const it = byName.get(name);
        it.events.push([at, qty]);
        return { itemId: it.id, name, qty, unitCost: it.cost, expiry: it.expiry, total: Math.round(qty * it.cost * 100) / 100 };
      });
      const total = Math.round(lines.reduce((a, l) => a + l.total, 0) * 100) / 100;
      await ctx.set(`shopBooks/${PH}/purchases/demo-buy-${++p}`, { at, day: B.dayKey(at), supplier, note: '', lines, total, createdAt: at });
      addInto(day(B.dayKey(at)), { purchases: 1, bought: total });
    }

    // ---------- নষ্ট / গণনা ----------
    const adjusts = [
      [12, 'writeoff', 'Orsaline-N', 5, 'প্যাকেট ভিজে নষ্ট'],
      [4, 'writeoff', 'Napa', 20, 'পাতা ছেঁড়া'],
      [8, 'count', 'Maxpro', -2, ''],
    ];
    for (const [k, kind, name, q] of adjusts) byName.get(name).events.push([today - k * DAY + 20 * 3600 * 1000, kind === 'writeoff' ? -q : q]);
    // কোনো সময়ে কয়টা ছিল = শেষের পরিমাণ − তার পরের সব বদল
    const qtyAt = (it, t) => it.finalQty - it.events.filter(([e]) => e > t).reduce((x, [, d]) => x + d, 0);
    let a = 0;
    for (const [k, kind, name, q, reason] of adjusts) {
      const it = byName.get(name);
      const at = today - k * DAY + 20 * 3600 * 1000;
      const loss = kind === 'writeoff' ? Math.round(q * it.cost * 100) / 100 : 0;
      const after = qtyAt(it, at);
      const before = after - (kind === 'writeoff' ? -q : q);
      await ctx.set(`shopBooks/${PH}/adjust/demo-adj-${++a}`, {
        kind, itemId: it.id, name, before, after, delta: kind === 'writeoff' ? -q : q, unitCost: it.cost, loss, reason, at, day: B.dayKey(at), createdAt: at,
      });
      addInto(day(B.dayKey(at)), kind === 'writeoff' ? { adjusts: 1, writeOff: loss, woq: q } : { adjusts: 1 });
    }

    // ---------- দিনের যোগফল ----------
    for (const [k, d] of days) await ctx.set(`shopBooks/${PH}/days/${k}`, { ...d, updatedAt: now });

    // ---------- পণ্য: শেষের পরিমাণ (৯০ দিন আগে খোলা) ----------
    for (const it of items) {
      await ctx.set(`shopItems/${PH}/items/${it.id}`, {
        name: it.name, key: B.key(it.name), generic: it.generic, form: it.form, unit: it.unit, qty: it.finalQty, cost: it.cost, price: it.price,
        disc: it.disc, lowAt: it.lowAt, expiry: it.expiry, lastSaleAt: it.lastSaleAt, createdAt: now - 90 * DAY, updatedAt: now - DAY,
      });
    }
  },
};
