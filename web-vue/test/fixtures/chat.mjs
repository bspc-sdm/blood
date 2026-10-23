// অ্যাপে মেসেজ (backend/docs/CHAT.md) — নকল উত্তর। অবস্থা রাখে না; ধাপে ধাপে বদল e2e/chat.spec.ts নিজে page.route দিয়ে দেয়।
// আমি = u1 (test/pages-এর লগইন), ওপাশ = d1 (ডোনার) / ph1 (ফার্মেসি)।
const MIN = 60 * 1000;
const now = Date.now();
const PEER = { uid: 'd1', name: 'সুমাইয়া আক্তার' };
const PHARM = { uid: 'ph1', name: 'লাজ ফার্মা' };

export const chatMsgs = {
  t1: [
    { id: 'm1', threadId: 't1', from: 'u1', text: 'আসসালামু আলাইকুম, আপনি কি আজ বিকেলে আসতে পারবেন?', at: now - 50 * MIN, readAt: now - 45 * MIN },
    { id: 'm2', threadId: 't1', from: 'd1', text: 'জি, ৪টার মধ্যে হাসপাতালে পৌঁছাব।', at: now - 40 * MIN },
    { id: 'm3', threadId: 't1', from: 'd1', location: { lat: 24.7471, lng: 90.4203 }, at: now - 39 * MIN },
    { id: 'm4', threadId: 't1', from: 'd1', text: 'রক্তের গ্রুপ B+ তো?', at: now - 5 * MIN },
  ],
  t2: [
    { id: 'p1', threadId: 't2', from: 'ph1', text: 'আপনার ওষুধ পাঠানো হয়েছে।', at: now - 3 * 24 * 60 * MIN, readAt: null },
    { id: 'p2', threadId: 't2', from: 'u1', unsent: true, at: now - 3 * 24 * 60 * MIN + MIN },
  ],
};
export const chatThreadsData = [
  { threadId: 't1', ctx: 'blood', refId: 'm1', peer: PEER, last: { text: 'রক্তের গ্রুপ B+ তো?', at: now - 5 * MIN, mine: false }, unread: 2, canSend: true },
  { threadId: 't2', ctx: 'rx_order', refId: 'o1', peer: PHARM, last: { text: '📷', at: now - 3 * 24 * 60 * MIN, mine: false }, unread: 0, canSend: false },
];
const info = (id) => {
  const t = chatThreadsData.find((x) => x.threadId === id) || chatThreadsData[0];
  return { threadId: t.threadId, ctx: t.ctx, refId: t.refId, peer: t.peer, canSend: t.canSend, ...(t.canSend ? {} : { closed: true }) };
};

export default {
  chat_unread: () => ({ ok: true, total: 2 }),
  chat_threads: () => ({ ok: true, items: chatThreadsData, next: null }),
  chat_open: (b) => {
    const id = b.threadId || (b.ctx === 'rx_order' ? 't2' : 't1');
    return { ok: true, ...info(id), messages: chatMsgs[id] || [], before: null };
  },
  chat_messages: (b) => (b.before ? { ok: true, ...info(b.threadId), messages: [], before: null } : { ok: true, ...info(b.threadId), messages: chatMsgs[b.threadId] || [], before: null }),
  chat_send: (b) => ({ ok: true, message: { id: `s_${b.clientId}`, threadId: b.threadId, from: 'u1', ...(b.text ? { text: b.text } : {}), ...(b.photo ? { photoThumb: b.photo } : {}), ...(b.location ? { location: b.location } : {}), at: Date.now(), readAt: null } }),
  chat_read: () => ({ ok: true }),
  chat_unsend: () => ({ ok: true }),
  chat_report: () => ({ ok: true }),
};
