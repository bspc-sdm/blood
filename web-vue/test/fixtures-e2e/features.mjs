// ফিচার সুইচ (backend/docs/FEATURES.md): সার্ভারের পরীক্ষার প্রোফাইলের মতো সব চালু। বন্ধের পরীক্ষা নিজে page.route
// (online-chamber.spec.ts) বা window.__lfTest.features (calls.spec.ts) দিয়ে বন্ধ করে।
const KEYS = ['calls_audio', 'calls_video', 'appt_video', 'chat', 'chat_media', 'online_chamber', 'inv_pharmacy', 'inv_lab', 'inv_chamber', 'inv_ambulance', 'my_expenses', 'desktop'];
export default {
  features: () => ({ ok: true, keys: Object.fromEntries(KEYS.map((k) => [k, true])), v: 1 }),
};
// অ্যাডমিন প্যানেল: আগের মতো সব বন্ধ (প্যানেলের মেনুর পরীক্ষা অনলাইন চেম্বারের পাতা ছাড়াই)
export const admin = {
  features: () => ({ ok: true, keys: {}, v: 1 }),
};
