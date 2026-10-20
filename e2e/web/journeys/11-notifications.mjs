// নোটিফিকেশন (পুশ) — প্রতিটা ধরন সার্ভারে ঠিক শিরোনাম, লেখা আর "চাপলে কোথায় যাবে"-র তথ্য (data) নিয়ে তৈরি হয় কি না।
// CI-তে PUSH_MODE=fake: FCM-এ কিছু যায় না, কিন্তু সার্ভারের পুশের পথ পুরোটা চলে — প্রতিটা পুশ ঘণ্টার তালিকায় (inbox_items, একই
// title/body/data যা FCM-এর data-বার্তায় যায়) লেখা হয়, আর যার ফোনের টোকেন আছে তার জন্য fake transport-এ বার্তা গোনা হয় (Spring-এর
// লগ → /__e2e/push)। তাই এখানে: প্রাপকের টোকেন রেজিস্টার (device_register), কাজটা করা, তারপর প্রাপকের inbox-এ নতুন সারি —
// ধরন, শিরোনাম, লেখা, data (requestId/rxId/reqId/questionId/kind/role …) মেলানো, আর fake transport-এ বার্তা বেড়েছে কি না।
// ২১টা ধরন (docs/test-plan/01-inventory.md ছ): request, response, request_removed, emergency, eligible, correction, care (দুই রূপ:
// আমন্ত্রণ আর রোগীর হয়ে কেয়ারগিভারকে — helper), support, hospital, badge, services (তালিকা আর অ্যাম্বুলেন্স ডাক — helper),
// amb_req_mine, answer, appointment, rx, rx_pharmacy, rx_lab, rx_review, med_find, med_find_mine।
// শেষে: ওয়েবের ঘণ্টার তালিকায় প্রতিটা নোটিফিকেশন চাপলে ঠিক পাতা খোলে কি না (Android-এর openFromData-এর একই গন্তব্য), আর পুরো
// রানের সব পুশ (E2E_PG_URL থাকলে ডাটাবেস থেকে) একই নিয়মে যাচাই। ফল: shots/push-matrix.json (Android Maestro-র যাত্রাও এটা পড়ে)।
import fs from 'node:fs';
import path from 'node:path';
import { act, demo, owner, post, sql, cronDaily, uidOf, apptForRx } from '../api.mjs';
import { BASE } from '../stack.mjs';
import { SHOTS, settle, go, vis } from '../lib.mjs';

export const meta = { no: 11, slug: 'notifications', titleBn: 'নোটিফিকেশন: ২১ ধরন — শিরোনাম, লেখা, চাপলে কোথায়' };

const DAY = 86400000;
const GZP = 'গাজীপুর';
const TAJ = 'শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল';
const AT = { lat: 23.999, lng: 90.42 };
const matrix = [];

/** Android-এর openFromData (MainActivity.kt) — নোটিফিকেশন চাপলে কোন পাতা */
export function androidTarget(d) {
  const t = d.type;
  if (d.requestId) return t === 'response' ? `MyRequestDetail(${d.requestId})` : `RequestDetail(${d.requestId})`;
  if (d.questionId) return `QuestionDetail(${d.questionId})`;
  switch (t) {
    case 'appointment': return d.role === 'provider' ? 'ChamberEdit' : d.role ? 'MyAppointments' : 'ChamberEdit|MyAppointments (badge)';
    case 'homevisit': case 'homecare': return 'Main(APPOINTMENT) › home care';
    case 'support': return 'Help';
    case 'amb_req_mine': return d.reqId ? `AmbulanceRequest(${d.reqId})` : 'Main(SERVICES)';
    case 'rx': return d.rxId || d.id ? `Rx(${d.rxId || d.id})` : 'MyPrescriptions';
    case 'rx_review': return 'RxReviews';
    case 'care': return d.asUid && d.rxId ? `CareFor(${d.asUid}) › Rx(${d.rxId})` : 'Care';
    case 'rx_pharmacy': return 'MyBusiness(pharmacy) › orders';
    case 'med_find': return 'MyBusiness(pharmacy) › finds';
    case 'rx_lab': return 'MyBusiness(lab) › orders';
    case 'med_find_mine': return 'Main(SERVICES) › pharmacy › medicine finder';
    case 'emergency': return 'Main(HOME) + emergency banner';
    case 'pharmacy_order': case 'pharmacy_mine': return 'MyBusiness(pharmacy) › orders';
    case 'services': return ['ambulance', 'pharmacy', 'bloodbank', 'lab'].includes(d.kind) ? `MyBusiness(${d.kind})` : 'Main(WORK|HOME)';
    default: return 'Main(HOME)';
  }
}

/** ওয়েবের ঘণ্টার তালিকা থেকে চাপলে কোথায় যাওয়া উচিত — অ্যাপের একই জায়গা (Android-এর সাথে মিলিয়ে) */
function webTarget(d) {
  const t = d.type;
  if (d.requestId) return t === 'response' ? `/my-requests/${d.requestId}` : `/request/${d.requestId}`;
  if (d.questionId) return `/health/question/${d.questionId}`;
  switch (t) {
    case 'appointment': return d.role === 'provider' ? '/work/pro' : '/my-appointments';
    case 'support': return d.ticketId ? `/help/${d.ticketId}` : '/help';
    case 'amb_req_mine': return d.reqId ? `/services/ambulance/call/${d.reqId}` : '/services/ambulance';
    case 'rx': return `/prescriptions/${d.rxId || d.id}`;
    case 'rx_review': return '/work/pro';
    case 'care': return d.asUid && d.rxId ? ['/care', `/prescriptions/${d.rxId}`] : '/care';
    case 'badge': return '/health/badge';
    case 'rx_pharmacy': return '/work/pharmacy';
    case 'med_find': return '/work/pharmacy/finds';
    case 'rx_lab': return '/work/lab';
    case 'med_find_mine': return '/services/pharmacy/medicine';
    case 'services': return d.kind ? `/work/${d.kind}` : '/work';
    default: return '/';
  }
}

const tok = (label) => `e2e-fcm-${label}-${'x'.repeat(48)}`;
async function register(who, label) {
  await act(who, 'device_register', { token: tok(label) });
}
async function fake() {
  return (await (await fetch(`${BASE}/__e2e/push`)).json()).sent;
}
async function inboxIds(who) {
  return new Set((await act(who, 'inbox')).items.map((i) => i.id));
}
const match = (v, want) => (want instanceof RegExp ? want.test(String(v ?? '')) : want === true ? v != null && v !== '' : String(v) === String(want));

/**
 * একটা পুশ: trigger() চালিয়ে প্রাপকের inbox-এ নতুন সারি (ধরন মেলে এমন) — শিরোনাম/লেখা/data মেলানো, fake FCM-এ বার্তা বেড়েছে কি না।
 * ফেরত: সেই সারি।
 */
async function expectPush(spec, trigger) {
  const before = await inboxIds(spec.to);
  const sent0 = await fake();
  const ctx = (await trigger()) || {};
  const want = typeof spec.data === 'function' ? spec.data(ctx) : spec.data || {};
  let item = null;
  for (let i = 0; i < 40 && !item; i++) {
    const items = (await act(spec.to, 'inbox')).items.filter((x) => !before.has(x.id) && x.data?.type === spec.type);
    item = items.find((x) => match(x.title, spec.title)) || null;
    if (!item && items.length && i > 10) throw new Error(`${spec.type}: নতুন পুশ এসেছে কিন্তু শিরোনাম মেলে না — "${items.map((x) => x.title).join('" / "')}" (আশা: ${spec.title})`);
    if (!item) await new Promise((r) => setTimeout(r, 250));
  }
  if (!item) throw new Error(`${spec.type}: ${spec.to.role}-এর কাছে পুশ আসেনি (আশা: ${spec.title})`);
  if (spec.body !== undefined && !match(item.body, spec.body)) throw new Error(`${spec.type}: লেখা মেলে না — "${item.body}" (আশা: ${spec.body})`);
  for (const [k, v] of Object.entries(want)) {
    if (v === null) {
      if (item.data[k] != null) throw new Error(`${spec.type}: data.${k} থাকার কথা নয় — ${item.data[k]}`);
    } else if (!match(item.data[k], v)) throw new Error(`${spec.type}: data.${k} = ${item.data[k]} (আশা: ${v})`);
  }
  // ফোনে যাওয়া (fake FCM): প্রাপকের টোকেন আছে, তাই বার্তা গোনা বাড়ে
  const sent1 = await fake();
  if (sent0 != null && sent1 != null && !(sent1 > sent0)) throw new Error(`${spec.type}: FCM-এ (fake) কোনো বার্তা যায়নি (${sent0} → ${sent1})`);
  matrix.push({
    check: spec.id, type: spec.type, to: spec.to.role, title: item.title, body: item.body, data: item.data,
    android: androidTarget(item.data), web: webTarget(item.data), id: item.id,
  });
  return item;
}

/** নতুন সত্যিকারের (ডেমো নয়) ব্যবহারকারী — emulator-এ অ্যাকাউন্ট, প্রোফাইল */
async function realUser(email, set) {
  const v = await (await fetch(`${BASE}/__e2e/custom-token?email=${encodeURIComponent(email)}`)).json();
  const r = await fetch(`${BASE}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=e2e`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: v.token, returnSecureToken: true }),
  }).then((x) => x.json());
  const uid = uidOf(r.idToken);
  const who = { token: r.idToken, uid, role: email.split('@')[0], email };
  await act(who, 'profile_update', { set: { lang: 'bn', ...set } });
  return who;
}

export async function run(J) {
  // ---------- প্রস্তুতি: মানুষ আর ফোনের টোকেন ----------
  let P; let D; let DOC; let PH; let LAB; let AMB; let CG; let O; let A; let B;
  await J.sc('PUSH-00', 'প্রস্তুতি: ডেমো রোল, দুজন নতুন (আসল) ব্যবহারকারী, সবার ফোনের টোকেন (device_register)', async () => {
    [P, D, DOC, PH, LAB, AMB, CG] = await Promise.all(['patient', 'donor', 'doctor', 'pharmacy', 'lab', 'ambulance', 'caregiver'].map((r) => demo(r)));
    O = await owner();
    const birth = Date.UTC(1994, 4, 10);
    A = await realUser('e2e-push-patient@lifolo-e2e.test', { name: 'ই২ই রোগী রুবেল', phone: '01711000501', ...AT, placeName: GZP, locationSource: 'district' });
    B = await realUser('e2e-push-donor@lifolo-e2e.test', {
      name: 'ই২ই ডোনার দীপা', phone: '01711000502', bloodGroup: 'B-', birthDateMillis: birth, weightKg: 58, notify: true, ...AT, placeName: GZP, locationSource: 'district',
    });
    for (const [w, l] of [[P, 'patient'], [D, 'donor'], [DOC, 'doctor'], [PH, 'pharmacy'], [LAB, 'lab'], [AMB, 'ambulance'], [CG, 'caregiver'], [A, 'real-a'], [B, 'real-b']]) await register(w, l);
    const rows = sql(`SELECT json_agg(json_build_object('uid', uid, 'token', token)) FROM device_tokens WHERE token LIKE 'e2e-fcm-%'`);
    if (rows && rows.length < 9) throw new Error(`device_tokens-এ ${rows.length}টা টোকেন (৯টা হওয়ার কথা)`);
  });

  // ---------- রক্ত (আসল দুনিয়া: A রক্ত চান, B ডোনার) ----------
  let reqId;
  await J.sc('PUSH-request', 'request — কাছের মিল আছে এমন ডোনারকে নতুন রিকোয়েস্ট (notify-request)', async () => {
    await expectPush({ id: 'PUSH-request', to: B, type: 'request', title: /^B- রক্ত দরকার/, data: (c) => ({ requestId: c.id }) }, async () => {
      const r = await act(A, 'request_create', {
        ...AT, patientGroup: 'B-', bags: 1, hospital: TAJ, placeName: GZP, neededByMillis: Date.now() + DAY, requesterPhone: '01711000501', requesterName: 'ই২ই রোগী রুবেল', note: 'ই২ই', urgent: false,
      });
      reqId = r.id;
      await post(A, '/api/notify-request', { requestId: r.id });
      return { id: r.id };
    });
  });
  await J.sc('PUSH-response', 'response — ডোনার রাজি হলে রক্তপ্রার্থীকে', async () => {
    await expectPush({ id: 'PUSH-response', to: A, type: 'response', title: 'একজন ডোনার রাজি হয়েছেন', body: /ই২ই ডোনার দীপা \(B-\) রক্ত দিতে রাজি/, data: { requestId: reqId } }, async () => {
      await post(B, '/api/respond', { requestId: reqId });
    });
  });
  await J.sc('PUSH-request-confirm', 'request — রক্তপ্রার্থী নিশ্চিত করলে ডোনারকে', async () => {
    await expectPush({ id: 'PUSH-request-confirm', to: B, type: 'request', title: 'আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন', data: { requestId: reqId } }, async () => {
      await act(A, 'confirm_donor', { requestId: reqId, donorUid: B.uid });
    });
  });
  await J.sc('PUSH-request_removed', 'request_removed — রিকোয়েস্ট মুছে ফেললে রাজি ডোনারকে', async () => {
    await expectPush({ id: 'PUSH-request_removed', to: B, type: 'request_removed', title: 'রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে', body: new RegExp(`${TAJ}-এর B- রিকোয়েস্টটা তুলে নেওয়া হয়েছে`), data: { requestId: null } }, async () => {
      await act(A, 'request_delete', { requestId: reqId });
    });
  });
  await J.sc('PUSH-emergency', 'emergency — অ্যাডমিনের জরুরি আহ্বান যোগ্য ডোনারকে', async () => {
    let id;
    await expectPush({ id: 'PUSH-emergency', to: B, type: 'emergency', title: 'জরুরি: B- রক্ত দরকার', data: (c) => ({ emergencyId: c.id }) }, async () => {
      const r = await act(O, 'admin_emergency_create', {
        district: GZP, hospitalId: null, hospitalName: 'ই২ই পুশ হাসপাতাল', groups: { 'B-': 1 }, radiusKm: 25, hours: 6, contactPhone: '01711000503', note: 'ই২ই', confirmed: true,
      });
      id = r.id || (await act(O, 'admin_emergencies')).items?.find((e) => e.hospital === 'ই২ই পুশ হাসপাতাল' || e.hospitalName === 'ই২ই পুশ হাসপাতাল')?.id;
      return { id: id || true };
    });
    if (id) await act(O, 'admin_emergency_close', { id }).catch(() => {});
  });

  // ---------- কেয়ারগিভার, সাপোর্ট, হাসপাতাল, ব্যাজ, তালিকা ----------
  await J.sc('PUSH-care-invite', 'care — কেয়ারগিভারের আমন্ত্রণ', async () => {
    await expectPush({ id: 'PUSH-care-invite', to: B, type: 'care', title: 'ই২ই রোগী রুবেল আপনাকে কেয়ারগিভার করতে চান', data: { asUid: null } }, async () => {
      await act(A, 'care_invite', { email: B.email });
    });
  });
  await J.sc('PUSH-care-accepted', 'care — কেয়ারগিভার রাজি হলে রোগীকে', async () => {
    await expectPush({ id: 'PUSH-care-accepted', to: A, type: 'care', title: 'ই২ই ডোনার দীপা এখন আপনার কেয়ারগিভার' }, async () => {
      await act(B, 'care_respond', { patientUid: A.uid, accept: true });
    });
    await act(A, 'care_remove', { caretakerUid: B.uid }).catch(() => act(A, 'care_remove', { uid: B.uid }).catch(() => {}));
  });
  await J.sc('PUSH-support', 'support — Lifolo টিমের উত্তর', async () => {
    let tid;
    await expectPush({ id: 'PUSH-support', to: A, type: 'support', title: 'Lifolo টিমের উত্তর এসেছে', body: '"সাহায্য ও আপিল" খুলে দেখুন', data: (c) => ({ ticketId: c.id }) }, async () => {
      const t = await act(A, 'ticket_create', { category: 'other', text: 'ই২ই: নোটিফিকেশন পরীক্ষার আপিল' });
      tid = t.id;
      await act(O, 'admin_ticket_reply', { id: t.id, text: 'ই২ই উত্তর: দেখছি', close: true });
      return { id: t.id };
    });
  });
  await J.sc('PUSH-hospital', 'hospital — হাসপাতাল যোগের অনুরোধ অনুমোদন', async () => {
    // প্রতি রানে নতুন নাম (আগের রানে যোগ হওয়া নামের অনুরোধ "আগে থেকেই আছে" হয়ে যায়)
    const name = `E2E Push Clinic ${Array.from({ length: 6 }, () => 'abcdefghijklmnopqrstuvwxyz'[Math.floor(Math.random() * 26)]).join('')}`;
    await expectPush({ id: 'PUSH-hospital', to: A, type: 'hospital', title: 'আপনার চাওয়া হাসপাতাল তালিকায় যোগ হয়েছে', body: name }, async () => {
      await act(A, 'hospital_request', { nameEn: name, type: 'clinic', district: GZP });
      const list = await act(O, 'admin_hospital_requests');
      const it = (list.items || []).find((x) => x.nameEn === name);
      if (!it) throw new Error('admin_hospital_requests-এ অনুরোধ নেই');
      await act(O, 'admin_hospital_resolve', { id: it.id, op: 'approve' });
    });
  });
  await J.sc('PUSH-badge', 'badge — অ্যাডমিন ব্যাজের কাগজ চাইলে', async () => {
    await expectPush({ id: 'PUSH-badge', to: A, type: 'badge', title: /^আপনার .+-এর কাগজের ছবি দিন$/, body: /ই২ই/ }, async () => {
      await act(A, 'badge_apply', { type: 'doctor', regNo: 'E2E-A-901', workplace: 'ই২ই হাসপাতাল' });
      await act(O, 'admin_request_docs', { kind: 'badge', id: A.uid, note: 'ই২ই: সনদের ছবি দিন' });
    });
    await act(O, 'admin_badge_resolve', { uid: A.uid, approve: false }).catch(() => {});
  });
  await J.sc('PUSH-services-listing', 'services — অ্যাডমিন অ্যাম্বুলেন্সের কাগজ চাইলে মালিককে (kind)', async () => {
    await expectPush({ id: 'PUSH-services-listing', to: AMB, type: 'services', title: /^আপনার .+-এর কাগজের ছবি দিন$/, data: { kind: 'ambulance' } }, async () => {
      await act(O, 'admin_request_docs', { kind: 'ambulance', id: 'demo-svc-amb-icu-new', note: 'ই২ই: ফিটনেসের ছবি' });
    });
  });

  // ---------- অ্যাম্বুলেন্স ডাকা (ডেমো: কেয়ারগিভার ডাকেন, ডেমো চালক) ----------
  let ambId;
  await J.sc('PUSH-services-amb', 'services (অ্যাম্বুলেন্স ডাক, helper) — কাছের চালককে', async () => {
    await expectPush({ id: 'PUSH-services-amb', to: AMB, type: 'services', title: /^অ্যাম্বুলেন্স দরকার — /, data: (c) => ({ kind: 'ambulance', sub: 'amb_req', reqId: c.id }) }, async () => {
      const r = await act(CG, 'ambreq_create', {
        district: GZP, upazilaId: 320, type: 'ac', dest: { kind: 'hospital', name: TAJ }, maxFare: null, includeUnknown: false, oxygen: false,
        pickup: 'ই২ই পুশ ঠিকানা', note: '', phone: '01700001109', ...AT, gps: true,
      });
      ambId = r.id;
      return { id: r.id };
    });
  });
  await J.sc('PUSH-amb_req_mine', 'amb_req_mine — চালক ভাড়া বললে রোগীকে', async () => {
    await expectPush({ id: 'PUSH-amb_req_mine', to: CG, type: 'amb_req_mine', title: 'ডেমো জীবন অ্যাম্বুলেন্স (এসি) ৳১১০০-তে আসতে রাজি', data: { reqId: ambId } }, async () => {
      await act(AMB, 'ambreq_accept', { id: ambId, fare: 1100 });
    });
    // রোগী "রাজি" → চালককে; তারপর রোগী বাতিল → চালককে
    await expectPush({ id: 'PUSH-services-amb-agreed', to: AMB, type: 'services', title: 'রোগী রাজি — ৳১১০০। এখনই রওনা দিন।', data: { reqId: ambId, kind: 'ambulance' } }, async () => {
      const st = await act(CG, 'ambreq_status', { id: ambId });
      const q = (st.request?.quotes || [])[0];
      if (!q) throw new Error('রোগীর পাতায় ভাড়ার প্রস্তাব নেই');
      await act(CG, 'ambreq_agree', { id: ambId, ambulanceId: q.id });
    });
    await expectPush({ id: 'PUSH-services-amb-cancel', to: AMB, type: 'services', title: 'রোগী অ্যাম্বুলেন্সের অনুরোধ বাতিল করেছেন', data: { reqId: ambId } }, async () => {
      await act(CG, 'ambreq_cancel', { id: ambId, reason: 'not_needed' });
    });
  });

  // ---------- প্রশ্নোত্তর, অ্যাপয়েন্টমেন্ট ----------
  await J.sc('PUSH-answer', 'answer — প্রশ্নের উত্তর এলে প্রশ্নকারীকে', async () => {
    await expectPush({ id: 'PUSH-answer', to: P, type: 'answer', title: 'আপনার প্রশ্নের উত্তর এসেছে', body: /ই২ই উত্তর/, data: (c) => ({ questionId: c.id }) }, async () => {
      const q = await act(P, 'ask', { text: 'ই২ই প্রশ্ন: জ্বর তিন দিন, কী পরীক্ষা করাব?' });
      const id = q.id || q.question?.id;
      await act(DOC, 'answer', { id, text: 'ই২ই উত্তর: CBC আর ডেঙ্গু NS1 করান।' });
      return { id };
    });
  });
  let apptId;
  await J.sc('PUSH-appointment', 'appointment — নতুন অনুরোধ ডাক্তারকে (provider), ফিরিয়ে দিলে রোগীকে (patient)', async () => {
    await expectPush({ id: 'PUSH-appointment-provider', to: DOC, type: 'appointment', title: 'নতুন অ্যাপয়েন্টমেন্টের অনুরোধ', data: { role: 'provider' } }, async () => {
      // তারিখ = ঢাকার দিনের UTC মধ্যরাত (সার্ভারের নিয়ম)
      const dhakaToday = Math.floor((Date.now() + 6 * 3600000) / DAY) * DAY;
      let last;
      for (let i = 1; i <= 8; i++) {
        try {
          const r = await act(D, 'book', { providerUid: DOC.uid, date: dhakaToday + i * DAY, chamberIndex: 0, note: 'ই২ই পুশ' });
          apptId = r.id;
          return;
        } catch (e) {
          last = e;
        }
      }
      throw last;
    });
    await expectPush({ id: 'PUSH-appointment-patient', to: D, type: 'appointment', title: 'অ্যাপয়েন্টমেন্ট নেওয়া হয়নি', data: { role: 'patient' } }, async () => {
      await act(DOC, 'appointment_update', { id: apptId, op: 'decline', providerNote: 'ই২ই' });
    });
  });

  // ---------- প্রেসক্রিপশন → ফার্মেসি, প্যাথলজি, রিপোর্ট, ডাক্তারের দেখা ----------
  let rxId;
  await J.sc('PUSH-rx', 'rx — ডাক্তার নতুন প্রেসক্রিপশন দিলে রোগীকে; care (helper) — কেয়ারগিভারকে রোগীর নামে, asUid+rxId', async () => {
    // অ্যাপের রোগীর কাছে প্রেসক্রিপশন সরাসরি যায় শুধু অ্যাপয়েন্টমেন্ট থেকে (কাগজের রোগীর বেলায় রোগী কোড দিয়ে যোগ করেন)
    const appointmentId = await apptForRx(DOC, P, 'ডেমো রোগী রহিম', 10);
    const careBefore = await inboxIds(CG);
    await expectPush({ id: 'PUSH-rx', to: P, type: 'rx', title: /^ডা\. ডেমো হাসান নতুন প্রেসক্রিপশন দিয়েছেন$/, data: (c) => ({ rxId: c.id }) }, async () => {
      const r = await act(DOC, 'rx_create', {
        appointmentId, complaints: 'ই২ই', diagnosis: 'ই২ই পরীক্ষা', medicines: [{ name: 'Napa 500', form: 'Tablet', dose: '1+1+1', days: 3, timing: 'after' }], tests: ['CBC'],
      });
      rxId = r.id;
      return { id: r.id };
    });
    // একই ঘটনায় কেয়ারগিভারকে (রোগীর নাম আগে, data: care + asUid + rxId)
    const it = (await act(CG, 'inbox')).items.find((x) => !careBefore.has(x.id) && x.data?.type === 'care' && x.data?.rxId === rxId);
    if (!it) throw new Error('কেয়ারগিভারের কাছে রোগীর প্রেসক্রিপশনের পুশ আসেনি');
    if (it.data.asUid !== P.uid) throw new Error(`care push asUid = ${it.data.asUid}`);
    if (!/^ডেমো রোগী রহিম: ডা\. ডেমো হাসান নতুন প্রেসক্রিপশন দিয়েছেন$/.test(it.title)) throw new Error(`care push শিরোনাম: ${it.title}`);
    matrix.push({ check: 'PUSH-care-helper', type: 'care', to: 'caregiver', title: it.title, body: it.body, data: it.data, android: androidTarget(it.data), web: webTarget(it.data), id: it.id });
  });
  await J.sc('PUSH-rx_pharmacy', 'rx_pharmacy — অনলাইন অর্ডার ফার্মেসিকে', async () => {
    const shop = (await act(PH, 'pharmacy_me')).pharmacy;
    await expectPush({ id: 'PUSH-rx_pharmacy', to: PH, type: 'rx_pharmacy', title: 'নতুন অনলাইন অর্ডার' }, async () => {
      await act(P, 'rx_send_pharmacy', { rxId, pharmacyId: shop.id, mode: 'online', address: 'ই২ই বাসা ৯, শিববাড়ি', phone: '01700001101' });
    });
  });
  let labOrder;
  await J.sc('PUSH-rx_lab', 'rx_lab — বাসা থেকে নমুনার অনুরোধ প্যাথলজিকে', async () => {
    const lab = (await act(LAB, 'lab_me')).lab;
    await expectPush({ id: 'PUSH-rx_lab', to: LAB, type: 'rx_lab', title: 'নতুন পরীক্ষা — বাসা থেকে নমুনা' }, async () => {
      await act(P, 'rx_send_lab', { rxId, labId: lab.id || LAB.uid, home: { address: 'ই২ই বাসা ৯, শিববাড়ি', phone: '01700001101', day: 1, slot: 'morning', note: '' } });
    });
    labOrder = (await act(LAB, 'lab_orders')).items.find((x) => x.rxId === rxId);
    if (!labOrder) throw new Error('lab_orders-এ নতুন অর্ডার নেই');
  });
  await J.sc('PUSH-rx-report', 'rx (রিপোর্ট) — রোগীকে; rx_review — ডাক্তারকে রিপোর্ট দেখার অনুরোধ', async () => {
    // প্যাথলজি বাসা থেকে নমুনার সময় নিশ্চিত করে (রোগীকে rx_lab-এর উত্তর), নমুনা নেয়, তারপর রিপোর্ট
    const dhakaToday = Math.floor((Date.now() + 6 * 3600000) / DAY) * DAY;
    await expectPush({ id: 'PUSH-rx-home-confirm', to: P, type: 'rx', title: /^ডেমো সিটি ডা.{1,3}গনস্টিক সেন্টার .+ আসবে$/, data: { rxId } }, async () => {
      await act(LAB, 'lab_home_confirm', { id: labOrder.id, date: dhakaToday + DAY, time: '08:00' });
    });
    await act(LAB, 'lab_order_status', { id: labOrder.id, status: 'received' });
    const docBefore = await inboxIds(DOC);
    await expectPush({ id: 'PUSH-rx-report', to: P, type: 'rx', title: 'আপনার পরীক্ষার রিপোর্ট এসেছে', data: { rxId } }, async () => {
      const f = (await act(LAB, 'lab_form', { id: labOrder.id })).forms[0];
      const p0 = f.params[0];
      await act(LAB, 'lab_report', {
        id: labOrder.id, note: '',
        items: [{ test: p0.name, result: '13.1', unit: p0.unit, range: p0.range, group: f.name, code: f.code, key: p0.key, low: p0.low, high: p0.high, flag: '' }],
      });
    });
    // ডাক্তারের রিপোর্ট দেখা: ফি না থাকলে রিপোর্ট আসামাত্র; ফি থাকলে (ডেমো ডাক্তার) রোগী TrxID দিলে — দুটোই rx_review
    let rv = null;
    for (let i = 0; i < 8 && !rv; i++) {
      rv = (await act(DOC, 'inbox')).items.find((x) => !docBefore.has(x.id) && x.data?.type === 'rx_review');
      if (!rv) await new Promise((r) => setTimeout(r, 250));
    }
    if (!rv) {
      rv = await expectPush({ id: 'PUSH-rx_review', to: DOC, type: 'rx_review', title: 'রিপোর্ট দেখার টাকা মিলিয়ে নিন', body: /E2E12345678/, data: { rxId, labOrderId: labOrder.id } }, async () => {
        await act(P, 'review_pay', { id: labOrder.id, trxId: 'E2E12345678', method: 'bkash' });
      });
      matrix.pop();
    } else if (rv.title !== 'একটা রিপোর্ট দেখার অপেক্ষায়') throw new Error(`rx_review শিরোনাম: ${rv.title}`);
    matrix.push({ check: 'PUSH-rx_review', type: 'rx_review', to: 'doctor', title: rv.title, body: rv.body, data: rv.data, android: androidTarget(rv.data), web: webTarget(rv.data), id: rv.id });
  });

  // ---------- ওষুধ খোঁজা ----------
  let findId;
  await J.sc('PUSH-med_find', 'med_find — "কোন দোকানে আছে?" কাছের ফার্মেসিকে', async () => {
    const drug = (await act(P, 'drug_search', { q: 'Napa' })).items?.[0];
    if (!drug) throw new Error('drug_search: Napa নেই');
    await expectPush({ id: 'PUSH-med_find', to: PH, type: 'med_find', title: 'কাছে একজন ওষুধ খুঁজছেন' }, async () => {
      const r = await act(P, 'med_find', { i: drug.i ?? drug.id, district: GZP, upazilaId: 320 });
      findId = r.id;
    });
  });
  await J.sc('PUSH-med_find_mine', 'med_find_mine — দোকান "আছে" বললে খুঁজছেন যিনি তাঁকে', async () => {
    const shop = (await act(PH, 'pharmacy_me')).pharmacy;
    await expectPush({ id: 'PUSH-med_find_mine', to: P, type: 'med_find_mine', title: `${shop.name}-এ আছে` }, async () => {
      const list = await act(PH, 'med_find_requests');
      const it = (list.items || []).find((x) => x.id === findId) || (list.items || [])[0];
      await act(PH, 'med_find_answer', { id: it.id, have: true });
    });
    await act(P, 'med_find_close', { id: findId }).catch(() => {});
  });

  // ---------- পুরোনো সংশোধনের অনুরোধ (অ্যাডমিন) ----------
  await J.sc('PUSH-correction', 'correction — তারিখ সংশোধনের অনুরোধের ফল (ডেমোর জমা থাকা অনুরোধ)', async () => {
    const list = await post(O, '/api/admin/corrections', {});
    const it = (list.items || [])[0];
    if (!it) throw new Error('জমা থাকা সংশোধনের অনুরোধ নেই (ডেমো দুটো রাখে)');
    const uid = it.uid;
    const before = sql(`SELECT coalesce(json_agg(id), '[]') FROM inbox_items WHERE uid = '${uid}'`) || [];
    await post(O, '/api/admin/resolve', { id: it.id, approve: false });
    const rows = sql(`SELECT json_agg(json_build_object('id', id, 'title', title, 'body', body, 'data', data)) FROM inbox_items WHERE uid = '${uid}'`) || [];
    const got = rows.find((x) => !before.includes(x.id) && x.data?.type === 'correction');
    if (process.env.E2E_PG_URL && !got) throw new Error(`${uid}-এর কাছে correction পুশ আসেনি`);
    if (got) {
      if (got.title !== 'তারিখ সংশোধনের অনুরোধ বাতিল হয়েছে') throw new Error(`correction শিরোনাম: ${got.title}`);
      matrix.push({ check: 'PUSH-correction', type: 'correction', to: uid, title: got.title, body: got.body, data: got.data, android: androidTarget(got.data), web: webTarget(got.data), id: got.id });
    }
  });

  // ---------- দৈনিক কাজ: আবার রক্ত দিতে পারবেন (eligible) + ১৩টা ধাপ সফল ----------
  await J.sc('PUSH-eligible', 'eligible — ১২০ দিন পর "আবার রক্ত দিতে পারবেন" (দৈনিক কাজ), দৈনিক কাজের সব ধাপ সফল', async () => {
    await act(B, 'donation_date_set', { millis: Date.now() - 120 * DAY - 2 * 3600000, confirmed: true });
    let daily;
    await expectPush({ id: 'PUSH-eligible', to: B, type: 'eligible', title: 'আপনি আবার রক্ত দিতে পারবেন', body: 'অ্যাপ খুলে দেখুন আপনার আশেপাশে কারো রক্ত লাগবে কি না।' }, async () => {
      daily = await cronDaily();
    });
    const steps = daily.results || daily.steps || daily;
    const failed = Object.entries(steps).filter(([, v]) => v === null);
    if (failed.length) throw new Error(`দৈনিক কাজের ধাপ ব্যর্থ: ${failed.map(([k]) => k).join(', ')}`);
  });

  // ---------- টোকেন: লগআউটে সরে, অন্য অ্যাকাউন্টে গেলে আগেরটায় থাকে না ----------
  await J.sc('PUSH-tokens', 'device_register/device_unregister — টোকেন এক ফোন এক অ্যাকাউন্ট; লগআউটে সরে', async () => {
    const t = tok('moving');
    await act(P, 'device_register', { token: t });
    await act(D, 'device_register', { token: t });
    const owners = sql(`SELECT json_agg(uid) FROM device_tokens WHERE token = '${t}'`);
    if (owners && (owners.length !== 1 || owners[0] !== D.uid)) throw new Error(`একই ফোনের টোকেন: ${JSON.stringify(owners)} (শুধু শেষ অ্যাকাউন্টে থাকার কথা)`);
    await act(D, 'device_unregister', { token: t });
    const left = sql(`SELECT coalesce(json_agg(uid), '[]') FROM device_tokens WHERE token = '${t}'`);
    if (left && left.length) throw new Error(`লগআউটের পরেও টোকেন আছে: ${JSON.stringify(left)}`);
    let bad = null;
    try { await act(P, 'device_register', { token: 'short' }); } catch (e) { bad = e; }
    if (!bad || bad.status !== 400) throw new Error('ভুল টোকেন নেওয়া হলো');
    // লগআউট করা টোকেনে আর পুশ যায় না: রোগীর টোকেন সরিয়ে একটা পুশ — inbox-এ আসে, fake FCM-এ বাড়ে না
    await act(P, 'device_unregister', { token: tok('patient') });
    const s0 = await fake();
    const q = await act(D, 'ask', { text: 'ই২ই টোকেন-পরীক্ষার প্রশ্ন: রক্ত দেওয়ার আগে কী খাব?' });
    await act(DOC, 'answer', { id: q.id || q.question?.id, text: 'ই২ই: স্বাভাবিক খাবার খান।' });
    await act(D, 'device_register', { token: tok('donor') });
    const s1 = await fake();
    if (s0 != null && s1 !== s0 + 1) throw new Error(`শুধু ডোনারের এক ফোনে যাওয়ার কথা — fake FCM ${s0} → ${s1}`);
    await act(P, 'device_register', { token: tok('patient') });
  });

  // ---------- ধরনের গোনা: ২১টা ----------
  await J.sc('PUSH-21', '২১টা ধরন (helper-সহ) সবগুলো যাচাই হয়েছে', async () => {
    const types = new Set(matrix.map((m) => (m.check === 'PUSH-care-helper' || m.check === 'PUSH-services-amb' ? `${m.type}:helper` : m.type)));
    const want = ['request', 'response', 'request_removed', 'emergency', 'eligible', 'correction', 'care', 'care:helper', 'support', 'hospital', 'badge', 'services', 'services:helper', 'amb_req_mine', 'answer', 'appointment', 'rx', 'rx_pharmacy', 'rx_lab', 'rx_review', 'med_find', 'med_find_mine'];
    const miss = want.filter((t) => !types.has(t));
    fs.mkdirSync(SHOTS, { recursive: true });
    fs.writeFileSync(path.join(SHOTS, 'push-matrix.json'), JSON.stringify(matrix, null, 1));
    if (miss.length) throw new Error(`যাচাই হয়নি: ${miss.join(', ')}`);
  });

  // ---------- পুরো রানের সব পুশ (ডাটাবেস) — একই নিয়ম ----------
  await J.sc('PUSH-audit', 'এই রানের সব পুশ: বাংলা শিরোনাম, ফাঁকা/ভাঙা লেখা নেই, চেনা ধরন, চাপলে যাওয়ার id আছে আর আসল', async () => {
    const rows = sql(`SELECT json_agg(json_build_object('uid', i.uid, 'title', i.title, 'body', i.body, 'data', i.data, 'lang', p.lang) ORDER BY i.id)
      FROM inbox_items i LEFT JOIN user_profiles p ON p.uid = i.uid WHERE i.created_at > now() - interval '6 hours'`);
    if (!rows) return; // ডাটাবেস দেখা যায় না (E2E_PG_URL নেই)
    const known = new Set(['request', 'response', 'request_removed', 'emergency', 'eligible', 'correction', 'care', 'support', 'hospital', 'badge', 'services', 'amb_req_mine', 'answer', 'appointment', 'rx', 'rx_pharmacy', 'rx_lab', 'rx_review', 'med_find', 'med_find_mine', 'homecare', 'homevisit', 'pharmacy_order', 'pharmacy_mine']);
    const exists = (table, col, id) => !!sql(`SELECT json_agg(1) FROM ${table} WHERE ${col} = '${String(id).replace(/'/g, "''")}'`);
    const bad = [];
    for (const r of rows) {
      const d = r.data || {};
      const why = [];
      if (!known.has(d.type)) why.push(`অচেনা ধরন ${d.type}`);
      if (!r.title || /\$\{|null|undefined|NaN/.test(r.title + (r.body || ''))) why.push('ভাঙা লেখা');
      if ((r.lang || 'bn') === 'bn' && !/[ঀ-৿]/.test(r.title) && !/^Lifolo$/.test(r.title)) why.push('বাংলা নয়');
      if (d.type === 'response' && !d.requestId) why.push('requestId নেই');
      if (d.type === 'answer' && !d.questionId) why.push('questionId নেই');
      if (d.type === 'amb_req_mine' && !d.reqId) why.push('reqId নেই');
      if (d.type === 'services' && d.sub === 'amb_req' && !d.reqId) why.push('reqId নেই');
      if (d.type === 'care' && d.rxId && !d.asUid) why.push('rxId আছে, asUid নেই');
      if (d.type === 'support' && !d.ticketId) why.push('ticketId নেই');
      if (d.type === 'appointment' && !d.role) why.push('role নেই');
      if (d.questionId && !exists('questions', 'id', d.questionId)) why.push(`প্রশ্ন ${d.questionId} নেই`);
      if (d.reqId && !exists('ambulance_requests', 'id', d.reqId)) why.push(`অ্যাম্বুলেন্স অনুরোধ ${d.reqId} নেই`);
      if (d.rxId && !exists('prescriptions', 'id', d.rxId)) why.push(`প্রেসক্রিপশন ${d.rxId} নেই`);
      if (why.length) bad.push(`${d.type} → ${r.uid}: "${r.title}" — ${why.join(', ')}`);
    }
    const byType = {};
    for (const r of rows) byType[r.data?.type] = (byType[r.data?.type] || 0) + 1;
    fs.writeFileSync(path.join(SHOTS, 'push-audit.json'), JSON.stringify({ total: rows.length, byType, bad }, null, 1));
    if (bad.length) throw new Error(`${bad.length}টা পুশ নিয়ম ভাঙে: ${bad.slice(0, 4).join(' | ')}`);
  });

  // ---------- ওয়েব: ঘণ্টার তালিকায় চাপলে ঠিক পাতা ----------
  /** এক জনের সব যাচাই করা নোটিফিকেশন: /inbox/<id> → "খুলুন" → পাতার ঠিকানা */
  async function webCheck(page, items) {
    for (const m of items) {
      await go(page, `/inbox/${encodeURIComponent(m.id)}`);
      await vis(page.getByRole('button', { name: 'খুলুন', exact: true })).first().click();
      await settle(page, 500);
      const at = new URL(page.url()).pathname;
      const wants = [].concat(m.web);
      m.webOpened = at;
      const ok = wants.some((w) => (w === '/' ? at === '/' : at === w || at.startsWith(`${w}/`) || at.startsWith(w)));
      if (ok) continue;
      throw new Error(`${m.type} "${m.title}": খুলল ${at} (আশা: ${wants.join(' বা ')})`);
    }
  }
  for (const role of ['patient', 'donor', 'doctor', 'pharmacy', 'lab', 'ambulance', 'caregiver']) {
    await J.sc(`PUSH-web-${role}`, `ওয়েব: ${role}-এর নোটিফিকেশন চাপলে ঠিক পাতা`, async () => {
      const items = matrix.filter((m) => m.to === role);
      if (!items.length) return;
      const page = await J.as(role, 'desktop');
      await webCheck(page, items);
      await J.shot(`${role}: নোটিফিকেশন থেকে শেষ খোলা পাতা`, 'প্রতিটা নোটিফিকেশন চাপলে অ্যাপের মতো একই জায়গা খোলে (push-matrix.json)।');
    });
  }
  for (const who of [A, B].filter(Boolean)) {
    await J.sc(`PUSH-web-${who.role}`, `ওয়েব: নতুন ব্যবহারকারী (${who.email}) — ইমেইলে লগইন, নোটিফিকেশন চাপলে ঠিক পাতা`, async () => {
      const items = matrix.filter((m) => m.to === who.role);
      const ctx = await (await J.ctxFor('desktop')).ctx.browser().newContext({ viewport: { width: 1366, height: 900 }, locale: 'bn-BD', timezoneId: 'Asia/Dhaka' });
      const page = await ctx.newPage();
      page.setDefaultTimeout(8000);
      try {
        await page.goto(`${BASE}/login`);
        const dlg = page.getByRole('dialog');
        await dlg.getByLabel('ইমেইল').fill(who.email);
        await dlg.getByRole('button', { name: 'কোড পাঠান' }).click();
        await dlg.getByLabel('৬ সংখ্যার কোড').waitFor();
        const { code } = await (await fetch(`${BASE}/__e2e/mail?to=${encodeURIComponent(who.email)}`)).json();
        await dlg.getByLabel('৬ সংখ্যার কোড').fill(code);
        await dlg.getByRole('button', { name: 'লগইন', exact: true }).click();
        await page.getByRole('dialog').waitFor({ state: 'detached', timeout: 15000 });
        await webCheck(page, items);
        await J.shot(`${who.role}: নোটিফিকেশন থেকে শেষ খোলা পাতা`, 'ইমেইলে লগইন করা নতুন ব্যবহারকারীর নোটিফিকেশনও ঠিক পাতায় যায়।', { page });
      } finally {
        await ctx.close();
      }
    });
  }
  fs.writeFileSync(path.join(SHOTS, 'push-matrix.json'), JSON.stringify(matrix, null, 1));
}
