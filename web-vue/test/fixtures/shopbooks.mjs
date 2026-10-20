// ফার্মেসির হিসাব-খাতার নকল উত্তর (/work/pharmacy/orders/stock, /buy, /sales, /books) — সার্ভারের আকারে (server/lib/shopbooks.js),
// সব নাম/সংখ্যা বানানো। দাম-ছাড়ের হিসাব সার্ভারের priceSale-এর মতো (ছোট করে)।
const D = 24 * 3600 * 1000;
const now = Date.now();
const r2 = (x) => Math.round((x + Number.EPSILON) * 100) / 100;
const ym = (ms) => new Date(ms + 6 * 3600 * 1000).toISOString().slice(0, 7);
const dk = (ms) => new Date(ms + 6 * 3600 * 1000).toISOString().slice(0, 10).replace(/-/g, '');
const err = (status, error) => ({ __status: status, body: { error } });
const udisc = (p, d) => (p == null || !d ? 0 : r2(Math.min(p, d.type === 'pct' ? (p * d.v) / 100 : d.v)));

let seq = 0;
const mk = (name, x = {}) => ({ id: `it${++seq}`, name, generic: '', form: 'Tablet', unit: 'piece', qty: null, cost: null, price: null, disc: null, lowAt: null, expiry: null, inStock: true, lastSaleAt: now - 2 * D, updatedAt: now - D, ...x });
let items = [
  mk('Ace Plus', { generic: 'Paracetamol + Caffeine', qty: 240, cost: 2, price: 2.5, lowAt: 50, expiry: ym(now + 300 * D) }),
  mk('Amdocal 5', { generic: 'Amlodipine 5 mg', qty: 160, cost: 3.8, price: 5, disc: { type: 'pct', v: 10 }, lowAt: 40, expiry: ym(now + 400 * D) }),
  mk('Azithrocin 500', { generic: 'Azithromycin 500 mg', qty: 18, cost: 30, price: 40, disc: { type: 'amt', v: 2 }, lowAt: 10, expiry: ym(now + 25 * D) }),
  mk('Calbo-D', { generic: 'Calcium + Vitamin D', qty: 120, cost: 8, price: 10.5, disc: { type: 'pct', v: 10 }, lowAt: 30, expiry: ym(now + 500 * D) }),
  mk('Cef-3 200', { generic: 'Cefixime 200 mg', form: 'Capsule', qty: 4, cost: 32, price: 40, lowAt: 10, expiry: ym(now + 200 * D) }),
  mk('Comet 500', { generic: 'Metformin 500 mg', qty: 300, cost: 3.6, price: 5, disc: { type: 'pct', v: 10 }, lowAt: 100, expiry: ym(now + 600 * D) }),
  mk('Fexo 120', { generic: 'Fexofenadine 120 mg', qty: 110, cost: 7, price: 9, disc: { type: 'amt', v: 0.5 }, lowAt: 30, expiry: ym(now + 400 * D) }),
  mk('Flagyl 400', { generic: 'Metronidazole 400 mg', qty: 130, cost: 2.4, price: 2, lowAt: 40, expiry: ym(now + 500 * D) }),
  mk('Montene 10', { generic: 'Montelukast 10 mg', qty: 8, cost: 13, price: 16, disc: { type: 'pct', v: 10 }, lowAt: 15, expiry: ym(now + 300 * D) }),
  mk('Napa', { generic: 'Paracetamol 500 mg', qty: 640, cost: 0.9, price: 1.2, lowAt: 200, expiry: ym(now + 420 * D) }),
  mk('Orsaline-N', { generic: 'Oral rehydration salt', form: 'Sachet', unit: 'sachet', qty: 180, cost: 4.2, price: 5, lowAt: 50, expiry: ym(now + 5 * D) }),
  mk('Seclo 20', { generic: 'Omeprazole 20 mg', form: 'Capsule', qty: 260, cost: 5.2, price: 7, disc: { type: 'pct', v: 10 }, lowAt: 60, expiry: ym(now + 600 * D) }),
  mk('Tusca Plus', { generic: 'Cough syrup', form: 'Syrup', unit: 'bottle', qty: 12, cost: 70, price: 90, lowAt: 5, lastSaleAt: now - 40 * D, expiry: ym(now + 200 * D) }),
  mk('Zimax 500'),
  mk('Thyrox 50', { qty: 0, cost: 1.8, price: 2.5, lowAt: 30, inStock: false }),
];
function view(i) {
  const exp = i.expiry ? Date.UTC(+i.expiry.slice(0, 4), +i.expiry.slice(5, 7), 1) - 1 : null;
  return {
    ...i, inStock: i.qty != null ? i.qty > 0 && i.inStock !== false : i.inStock !== false,
    salePrice: i.price == null ? null : r2(i.price - udisc(i.price, i.disc)),
    low: i.qty != null && i.lowAt != null && i.qty <= i.lowAt, expDays: exp == null ? null : Math.floor((exp - now) / D),
  };
}
const list = () => [...items].sort((a, b) => a.name.localeCompare(b.name)).map(view);

function priceSale(lines, bill) {
  const L = lines.map((l) => {
    const priced = l.unitPrice != null;
    const gross = priced ? r2(l.qty * l.unitPrice) : 0;
    const ld = priced ? r2(Math.min(gross, l.qty * (l.unitDisc || 0))) : 0;
    return { ...l, gross, ld, after: r2(gross - ld) };
  });
  const sum = L.reduce((a, l) => a + l.after, 0);
  const bd = bill && sum > 0 ? r2(Math.min(sum, bill.type === 'pct' ? (sum * bill.v) / 100 : bill.v)) : 0;
  const out = L.map((l) => {
    const net = r2(l.after - (sum > 0 ? (bd * l.after) / sum : 0));
    const cost = l.unitCost != null ? r2(l.qty * l.unitCost) : null;
    return { itemId: l.itemId, name: l.name, qty: l.qty, unitPrice: l.unitPrice ?? null, unitDisc: l.unitDisc || 0, discount: r2(l.ld), net, unitCost: l.unitCost ?? null, cost, profit: l.unitPrice != null && cost != null ? r2(net - cost) : null };
  });
  const s = (f) => r2(out.reduce((a, l) => a + (f(l) || 0), 0));
  return { lines: out, totals: { gross: s((l) => (l.unitPrice ?? 0) * l.qty), discount: r2(L.reduce((a, l) => a + l.ld, 0) + bd), billDiscount: bd, net: s((l) => l.net), cost: s((l) => (l.profit != null ? l.cost : 0)), profit: s((l) => l.profit), netCosted: s((l) => (l.profit != null ? l.net : 0)), unpriced: out.filter((l) => l.unitPrice == null).length } };
}
const byName = (n) => items.find((i) => i.name === n);
const line = (n, qty, extra = {}) => { const i = byName(n); return { itemId: i.id, name: n, qty, unitPrice: i.price, unitDisc: udisc(i.price, i.disc), unitCost: i.cost, ...extra }; };
let sales = [
  [20, 'counter', [line('Napa', 20), line('Seclo 20', 14)], null, 'ডেমো ক্রেতা রাকিব'],
  [55, 'rx', [line('Comet 500', 60), line('Amdocal 5', 30)], null, '', 'রহিমা খাতুন'],
  [90, 'counter', [line('Fexo 120', 10)], { type: 'amt', v: 5 }],
  [140, 'online', [line('Montene 10', 15), line('Calbo-D', 30)], { type: 'pct', v: 5 }, '', 'শাহনাজ পারভীন'],
  [200, 'counter', [line('Flagyl 400', 21)]],
  [260, 'counter', [line('Ace Plus', 10), line('Orsaline-N', 5)]],
].map(([min, source, lines, bill, customer = '', patient = ''], k) => ({
  id: `s${k + 1}`, at: now - min * 60000, day: dk(now - min * 60000), source, ...priceSale(lines, bill), billDisc: bill, customer,
  ...(patient ? { rx: { code: 'H4QW7ZK2MP', patient } } : {}),
}));
let purchases = [
  { id: 'p1', at: now - 2 * D, supplier: 'স্কয়ার ডিস্ট্রিবিউটর', note: '', lines: [{ itemId: byName('Napa').id, name: 'Napa', qty: 400, unitCost: 0.9, expiry: null, total: 360 }, { itemId: byName('Cef-3 200').id, name: 'Cef-3 200', qty: 20, unitCost: 32, expiry: null, total: 640 }], total: 1000 },
  { id: 'p2', at: now - 6 * D, supplier: 'রেনাটা ডিপো', note: '', lines: [{ itemId: byName('Seclo 20').id, name: 'Seclo 20', qty: 140, unitCost: 5.2, expiry: null, total: 728 }, { itemId: byName('Orsaline-N').id, name: 'Orsaline-N', qty: 150, unitCost: 4.2, expiry: null, total: 630 }], total: 1358 },
  { id: 'p3', at: now - 14 * D, supplier: '', note: '', lines: [{ itemId: byName('Montene 10').id, name: 'Montene 10', qty: 60, unitCost: 13, expiry: null, total: 780 }], total: 780 },
];

function report(period) {
  const n = period === 'today' ? 1 : period === '30d' ? 30 : period === 'month' ? Number(dk(now).slice(6)) : 7;
  const daily = Array.from({ length: n }, (_, i) => {
    const k = n - 1 - i;
    const net = k === 0 ? r2(sales.reduce((a, s) => a + s.totals.net, 0)) : 1200 + ((k * 397) % 900);
    return { day: dk(now - k * D), net, profit: r2(net * (0.17 + ((k * 13) % 7) / 100)), sales: k === 0 ? sales.length : 6 + (k % 5) };
  });
  const net = r2(daily.reduce((a, d) => a + d.net, 0));
  const profit = r2(daily.reduce((a, d) => a + d.profit, 0));
  const writeOff = n >= 7 ? 18 : 0;
  const L = list();
  const live = L.filter((i) => i.qty > 0);
  const rank = (name, net2, p, extra = {}) => ({ id: byName(name).id, name, qty: 40, net: net2, profit: p, loss: 0, lossQty: 0, ...extra });
  return {
    period, from: daily[0].day, to: daily[daily.length - 1].day,
    totals: { sales: daily.reduce((a, d) => a + d.sales, 0), gross: r2(net * 1.05), discount: r2(net * 0.05), net, cost: r2(net - profit), profit, margin: Math.round((profit / net) * 1000) / 10, uncosted: 0, writeOff, netProfit: r2(profit - writeOff), bought: n >= 7 ? 2358 : 0, purchases: n >= 7 ? 2 : 0 },
    bySource: { counter: { n: Math.round(daily.length * 6), net: r2(net * 0.76) }, rx: { n: daily.length, net: r2(net * 0.19) }, online: { n: Math.ceil(daily.length / 4), net: r2(net * 0.05) } },
    daily,
    topSales: [rank('Comet 500', r2(net * 0.12), r2(net * 0.03)), rank('Seclo 20', r2(net * 0.09), r2(net * 0.02)), rank('Calbo-D', r2(net * 0.08), r2(net * 0.017)), rank('Napa', r2(net * 0.06), r2(net * 0.015)), rank('Amdocal 5', r2(net * 0.05), r2(net * 0.01))],
    topProfit: [rank('Comet 500', r2(net * 0.12), r2(net * 0.03)), rank('Seclo 20', r2(net * 0.09), r2(net * 0.02)), rank('Calbo-D', r2(net * 0.08), r2(net * 0.017))],
    belowCost: [rank('Flagyl 400', 42, -8.4, { loss: 8.4, lossQty: 21, qty: 21 })],
    stock: { items: L.length, counted: L.filter((i) => i.qty != null).length, atCost: r2(live.reduce((a, i) => a + (i.cost != null ? i.qty * i.cost : 0), 0)), atPrice: r2(live.reduce((a, i) => a + (i.price != null ? i.qty * i.price : 0), 0)), noCost: 0, noPrice: 0 },
    low: L.filter((i) => i.low), expiring: L.filter((i) => i.expDays != null && i.expDays <= 60), noSale: L.filter((i) => i.qty > 0 && i.lastSaleAt < now - 30 * D),
  };
}

export default {
  books_items: () => ({ items: list(), units: ['piece', 'strip', 'box', 'bottle', 'tube', 'vial', 'sachet', 'other'] }),
  books_item_save: (b) => {
    if (!String(b.name || '').trim()) return err(400, 'পণ্যের নাম লিখুন');
    const ex = b.id ? items.find((i) => i.id === b.id) : null;
    if (!ex && items.some((i) => i.name.toLowerCase() === b.name.trim().toLowerCase())) return err(409, 'এই নামে আগেই একটা পণ্য আছে');
    const x = { name: b.name.trim(), generic: b.generic || '', form: b.form || '', unit: b.unit || '', cost: b.cost ?? null, price: b.price ?? null, disc: b.disc || null, lowAt: b.lowAt ?? null, expiry: b.expiry || null, updatedAt: Date.now() };
    if (ex) Object.assign(ex, x, 'qty' in b ? { qty: b.qty } : {});
    else items.push(mk(x.name, { ...x, qty: b.qty ?? null, lastSaleAt: null }));
    return { item: view(ex || items[items.length - 1]) };
  },
  books_item_stock: (b) => { const i = items.find((x) => x.id === b.id); if (!i) return err(404, 'পাওয়া যায়নি'); i.inStock = b.on === true; return { item: view(i) }; },
  books_item_delete: (b) => { items = items.filter((i) => i.id !== b.id); return { ok: true }; },
  books_purchases: () => ({ items: purchases }),
  books_purchase_add: (b) => {
    const lines = (b.lines || []).map((l) => {
      let i = l.itemId ? items.find((x) => x.id === l.itemId) : byName(l.name);
      if (!i) { i = mk(l.name, { qty: 0, lastSaleAt: null }); items.push(i); }
      const have = i.qty || 0;
      i.cost = l.unitCost == null ? i.cost : i.cost != null && have > 0 ? r2((have * i.cost + l.qty * l.unitCost) / (have + l.qty)) : l.unitCost;
      i.qty = have + l.qty;
      if (l.expiry) i.expiry = l.expiry;
      return { itemId: i.id, name: i.name, qty: l.qty, unitCost: l.unitCost ?? null, expiry: l.expiry || null, total: l.unitCost != null ? r2(l.qty * l.unitCost) : null };
    });
    if (!lines.length) return err(400, 'অন্তত একটা পণ্য আর পরিমাণ দিন');
    const p = { id: `p${purchases.length + 10}`, at: Date.now(), supplier: b.supplier || '', note: '', lines, total: r2(lines.reduce((a, l) => a + (l.total || 0), 0)) };
    purchases = [p, ...purchases];
    return { id: p.id, purchase: p };
  },
  books_purchase_delete: (b) => { purchases = purchases.filter((p) => p.id !== b.id); return { ok: true }; },
  books_sales: (b) => ({ items: b.period === 'today' ? sales : [...sales, ...sales.map((s) => ({ ...s, id: s.id + 'y', at: s.at - 2 * D }))] }),
  books_sale_add: (b) => {
    let src = 'counter';
    let lines;
    if (b.source === 'rx') {
      src = 'rx';
      lines = (b.lines || []).map((l) => { const i = items.find((x) => x.id === l.itemId); return { itemId: l.itemId || 'n' + l.i, name: i ? i.name : 'Medicine', qty: 10, unitPrice: l.unitPrice ?? null, unitDisc: l.unitDisc || 0, unitCost: i ? i.cost : null }; });
    } else {
      lines = (b.lines || []).filter((l) => l.qty > 0).map((l) => { const i = items.find((x) => x.id === l.itemId); return { itemId: i.id, name: i.name, qty: l.qty, unitPrice: l.unitPrice ?? i.price, unitDisc: l.unitDisc ?? udisc(i.price, i.disc), unitCost: i.cost }; });
    }
    if (!lines.length) return err(400, 'অন্তত একটা পণ্য আর পরিমাণ দিন');
    for (const l of lines) { const i = items.find((x) => x.id === l.itemId); if (i && i.qty != null) i.qty = Math.max(0, i.qty - l.qty); }
    const s = { id: `s${sales.length + 20}`, at: Date.now(), day: dk(Date.now()), source: src, ...priceSale(lines, b.billDisc || null), billDisc: b.billDisc || null, customer: '' };
    sales = [s, ...sales];
    return { id: s.id, sale: s };
  },
  books_sale_delete: (b) => { sales = sales.filter((s) => s.id !== b.id); return { ok: true }; },
  books_adjust: (b) => {
    const i = items.find((x) => x.id === b.itemId);
    if (!i) return err(404, 'পাওয়া যায়নি');
    const before = i.qty;
    i.qty = b.kind === 'count' ? b.qty : Math.max(0, (i.qty || 0) - b.qty);
    return { id: 'a1', adjust: { kind: b.kind, name: i.name, before, after: i.qty, loss: b.kind === 'writeoff' && i.cost != null ? r2(b.qty * i.cost) : 0 } };
  },
  books_adjusts: () => ({ items: [] }),
  books_report: (b) => report(['today', '7d', '30d', 'month'].includes(b.period) ? b.period : '30d'),
  books_rx_preview: (b) => ({
    code: b.code, at: b.at || Date.now(), source: 'rx', recorded: false, patient: 'রহিমা খাতুন',
    lines: [
      { i: 0, name: 'Amdocal', qty: 20, itemId: byName('Amdocal 5').id, itemName: 'Amdocal 5', unitPrice: 5, unitDisc: 0.5, unitCost: 3.8, stockQty: 160 },
      { i: 1, name: 'Comet', qty: 60, itemId: byName('Comet 500').id, itemName: 'Comet 500', unitPrice: 5, unitDisc: 0.5, unitCost: 3.6, stockQty: 300 },
      { i: 2, name: 'Seclo', qty: 28, itemId: null, itemName: '', unitPrice: null, unitDisc: 0, unitCost: null, stockQty: null },
    ],
  }),
};
