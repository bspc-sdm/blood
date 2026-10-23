// বাকি যে action-গুলো আগে কোনো end-to-end পথে চলত না (docs/test-plan/02-coverage-matrix.md "ফাঁক ১") — আসল সার্ভারে, API দিয়ে
// (ব্রাউজারে বোতাম থাকলে সেটা আগের যাত্রায়)। প্রতিটা দৃশ্যে শুধু "সফল" নয় — ফলটা আবার পড়ে মেলানো (তালিকায় এল/গেল, অবস্থা বদলাল)।
// নতুন দোকান/প্যাথলজি/ব্লাড ব্যাংক/অ্যাম্বুলেন্স: একজন নতুন (আসল) ব্যবহারকারী বানিয়ে মুছে ফেলেন — ডেমো অক্ষত থাকে।
// শেষ দৃশ্যে ডেমো মোছা (admin_demo_wipe — রিলিজের আগে যেটা করতে হবে, আসল অ্যাকাউন্ট-মোছার পথ) — তাই এই যাত্রা সবার শেষে।
import { act, demo, owner, post, sql, uidOf, ApiError, apptForRx, ensureConsent } from '../api.mjs';
import { BASE } from '../stack.mjs';
import { go, see, settle, logout, demoConsents } from '../lib.mjs';
import { adminLogin } from './00-seed.mjs';

export const meta = { no: 12, slug: 'more-actions', titleBn: 'বাকি কাজ: সাপোর্ট, তালিকা মোছা, হিসাব-খাতা, প্রশ্নোত্তর, ল্যাব, ফার্মেসি, ডেমো মোছা' };

const GZP = 'গাজীপুর';
const AT = { lat: 23.999, lng: 90.42 };
// ছোট আসল ধাঁচের JPEG (সার্ভার শুরুর বাইট দেখে)
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(200)]).toString('base64');

async function realUser(email, set) {
  const v = await (await fetch(`${BASE}/__e2e/custom-token?email=${encodeURIComponent(email)}`)).json();
  const r = await fetch(`${BASE}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=e2e`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: v.token, returnSecureToken: true }),
  }).then((x) => x.json());
  const who = { token: r.idToken, uid: uidOf(r.idToken), role: email.split('@')[0], email };
  // সম্মতির সুইচ চালু থাকলে রক্তের গ্রুপ/ওজন/রক্তদানের তারিখ দেওয়ার আগে সম্মতি (নতুন মানুষ, প্রস্তুতি)
  await ensureConsent(who);
  await act(who, 'profile_update', { set: { lang: 'bn', ...set } });
  return who;
}
async function fails(who, action, body, status) {
  try {
    await act(who, action, body);
  } catch (e) {
    if (e instanceof ApiError && (!status || e.status === status)) return e;
    throw e;
  }
  throw new Error(`${action}: সফল হলো, কিন্তু আটকানোর কথা`);
}
const eq = (a, b, what) => {
  if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(`${what}: ${JSON.stringify(a)} (আশা: ${JSON.stringify(b)})`);
};

export async function run(J) {
  let P; let D; let DOC; let PH; let LAB; let AMB; let BB; let O; let U;
  await J.sc('MORE-00', 'প্রস্তুতি', async () => {
    [P, D, DOC, PH, LAB, AMB, BB] = await Promise.all(['patient', 'donor', 'doctor', 'pharmacy', 'lab', 'ambulance', 'bloodbank'].map((r) => demo(r)));
    O = await owner();
    await demoConsents();
    U = await realUser('e2e-more-owner@lifolo-e2e.test', { name: 'ই২ই ব্যবসায়ী', phone: '01711000601', ...AT, placeName: GZP, locationSource: 'district' });
  });

  // ---------------- অ্যাডমিন ----------------
  await J.sc('MORE-admin', 'অ্যাডমিন: অ্যাডমিনের তালিকা, রিপোর্ট, আপিলের তালিকা (উত্তর দেওয়া), বাছাইয়ের তালিকা সেভ, পরীক্ষার ছক', async () => {
    const list = await act(O, 'admin_list');
    if (!(list.owners || []).includes('ekapkbd@gmail.com')) throw new Error(`admin_list: মালিক নেই — ${JSON.stringify(list).slice(0, 120)}`);
    const reports = await act(O, 'admin_reports');
    if (!Array.isArray(reports.items)) throw new Error('admin_reports: items নেই');
    const answered = await act(O, 'admin_tickets', { status: 'answered' });
    if (!Array.isArray(answered.items)) throw new Error('admin_tickets: items নেই');
    // বাছাইয়ের তালিকা: এখনকারটাই আবার সেভ (কিছু বদলায় না) — তারপর পড়ে মেলানো
    const cat = (await act(P, 'catalog_get')).lists.specialty.doctor;
    await act(O, 'admin_catalog_set', { kind: 'specialty', type: 'doctor', items: cat });
    eq((await act(P, 'catalog_get')).lists.specialty.doctor.map((x) => x.code), cat.map((x) => x.code), 'admin_catalog_set-এর পরে তালিকা');
    // রিপোর্টের ছক: নতুন পরীক্ষা যোগ — প্যাথলজির ফর্মে আসে
    const t = await act(O, 'admin_labtests_set', { test: { name: 'E2E Serum Zinc', aliases: ['e2e zinc'], params: [{ name: 'Zinc', unit: 'µg/dL', r: [60, 120] }] } });
    if (!/zinc/.test(t.test?.code || '')) throw new Error(`admin_labtests_set: ${JSON.stringify(t).slice(0, 120)}`);
    const tests = await act(LAB, 'labtests_get');
    if (!JSON.stringify(tests).includes('E2E Serum Zinc')) throw new Error('নতুন পরীক্ষা labtests_get-এ নেই');
  });

  await J.sc('MORE-bloodbank-admin', 'অ্যাডমিন: মালিক ছাড়া ব্লাড ব্যাংক যোগ, বদল, তালিকা, মোছা', async () => {
    const bank = { termsAccepted: true, name: 'ই২ই সরকারি ব্লাড ব্যাংক', kind: 'govt', phone: '01711000602', district: GZP, address: 'ই২ই হাসপাতাল', licenseNo: 'E2E-BB-1', open24: true, services: ['whole_blood'] };
    const added = await act(O, 'admin_bloodbank_add', bank);
    const id = added.id || added.bank?.id;
    if (!id) throw new Error(`admin_bloodbank_add: id নেই — ${JSON.stringify(added).slice(0, 120)}`);
    await act(O, 'admin_bloodbank_edit', { ...bank, id, phone: '01711000603', name: 'ই২ই সরকারি ব্লাড ব্যাংক (বদল)' });
    const approved = await act(O, 'admin_bloodbanks', { status: 'approved' });
    const row = (approved.items || []).find((x) => x.id === id);
    if (!row || !/বদল/.test(row.name)) throw new Error('admin_bloodbanks-এ বদলানো নাম নেই');
    const pub = await act(P, 'bloodbanks', { district: GZP });
    if (!JSON.stringify(pub).includes('ই২ই সরকারি ব্লাড ব্যাংক (বদল)')) throw new Error('সবার তালিকায় (bloodbanks) নেই');
    await act(P, 'bloodbank_called', { id });
    await act(O, 'admin_bloodbank_delete', { id, reason: 'ই২ই: পরীক্ষা শেষ' });
    if (JSON.stringify(await act(P, 'bloodbanks', { district: GZP })).includes('ই২ই সরকারি ব্লাড ব্যাংক')) throw new Error('মোছার পরেও তালিকায়');
  });

  // ---------------- নিজের তালিকা বানানো আর মোছা (নতুন ব্যবহারকারী) ----------------
  await J.sc('MORE-own-listings', 'নতুন ব্যবহারকারী: ফার্মেসি, প্যাথলজি, ব্লাড ব্যাংক, অ্যাম্বুলেন্স যোগ (কাগজসহ) — কাগজ আবার দেওয়া — তারপর নিজে মোছা', async () => {
    await act(U, 'pharmacy_save', {
      name: 'ই২ই ফার্মেসি', licenseNo: 'E2E-DL-1', phone: '01711000604', district: GZP, upazilaId: 320, address: 'ই২ই বাজার', ...AT,
      openAt: '09:00', closeAt: '21:00', closedDays: [5], termsAccepted: true, docs: { license: JPEG, front: JPEG },
    });
    const ph = (await act(U, 'pharmacy_me')).pharmacy;
    if (!ph || ph.status !== 'pending') throw new Error(`pharmacy_me: ${JSON.stringify(ph).slice(0, 120)}`);
    // অ্যাডমিন কাগজ চান → মালিক কাগজ দেন (verify_docs_upload)
    await act(O, 'admin_request_docs', { kind: 'pharmacy', id: ph.id || U.uid, note: 'ই২ই: লাইসেন্সের পরিষ্কার ছবি' });
    await act(U, 'verify_docs_upload', { kind: 'pharmacy', id: ph.id || U.uid, docs: { license: JPEG } });
    await act(U, 'pharmacy_delete');
    if ((await act(U, 'pharmacy_me')).pharmacy) throw new Error('pharmacy_delete-এর পরেও দোকান আছে');

    await act(U, 'lab_save', {
      name: 'ই২ই প্যাথলজি', district: GZP, upazilaId: 320, address: 'ই২ই রোড', licenseNo: 'E2E-DGHS-1', phone: '01711000605', termsAccepted: true,
      hours: 'সকাল ৮টা – রাত ১০টা', homeCollection: true, docs: { license: JPEG, front: JPEG },
    });
    if (!(await act(U, 'lab_me')).lab) throw new Error('lab_save-এর পরে lab_me খালি');
    await act(U, 'lab_delete');
    if ((await act(U, 'lab_me')).lab) throw new Error('lab_delete-এর পরেও প্যাথলজি আছে');

    await act(U, 'bloodbank_save', {
      termsAccepted: true, name: 'ই২ই নিজের ব্লাড ব্যাংক', kind: 'private', phone: '01711000606', district: GZP, address: 'ই২ই', licenseNo: 'E2E-BB-2',
      open24: true, services: ['whole_blood'], docs: { front: JPEG, license: JPEG },
    });
    const bb = (await act(U, 'bloodbank_mine')).items?.[0];
    if (!bb) throw new Error('bloodbank_mine খালি');
    await act(U, 'bloodbank_delete', { id: bb.id });
    if ((await act(U, 'bloodbank_mine')).items?.length) throw new Error('bloodbank_delete-এর পরেও আছে');

    // ডেমো চালকের অনুমোদনের অপেক্ষার আইসিইউ গাড়ি — চালক নিজে মোছেন
    const before = (await act(AMB, 'ambulance_mine')).items.map((x) => x.id);
    if (!before.includes('demo-svc-amb-icu-new')) throw new Error('ডেমো চালকের অপেক্ষার গাড়ি নেই');
    await act(AMB, 'ambulance_delete', { id: 'demo-svc-amb-icu-new' });
    if ((await act(AMB, 'ambulance_mine')).items.some((x) => x.id === 'demo-svc-amb-icu-new')) throw new Error('ambulance_delete-এর পরেও গাড়ি আছে');
  });

  // ---------------- ফার্মেসির হিসাব-খাতা আর দোকানের সেটিং ----------------
  await J.sc('MORE-shop', 'ফার্মেসি: আজকের কার্ড, স্টক, স্টকে আছে/নেই, পণ্য মোছা, সমন্বয়ের তালিকা, অনলাইনের সেটিং, ওষুধ খোঁজা বন্ধ/চালু, দোকানের কোড', async () => {
    const today = await act(PH, 'books_today');
    if (typeof today !== 'object' || today === null) throw new Error('books_today');
    const stock = await act(PH, 'stock_get');
    if (!Array.isArray(stock.names || stock.items)) throw new Error(`stock_get: ${JSON.stringify(stock).slice(0, 100)}`);
    await act(PH, 'books_adjusts');
    // নতুন পণ্য → স্টকে নেই/আছে → মোছা
    const saved = await act(PH, 'books_item_save', { name: 'E2E Zincovit', generic: 'Zinc', form: 'Tablet', unit: 'piece', price: 3 });
    const id = saved.id || saved.item?.id || (await act(PH, 'books_items')).items.find((x) => x.name === 'E2E Zincovit')?.id;
    if (!id) throw new Error('books_item_save: id নেই');
    await act(PH, 'books_item_stock', { id, inStock: false }).catch(async (e) => {
      // পরিমাণ রাখা দোকানে হাতে চালু/বন্ধ নয় — সেটাও সঠিক উত্তর (400/409)
      if (!(e instanceof ApiError) || e.status >= 500) throw e;
    });
    await act(PH, 'books_item_delete', { id });
    if ((await act(PH, 'books_items')).items.some((x) => x.id === id)) throw new Error('books_item_delete-এর পরেও আছে');
    // অনলাইনের সেটিং — এখনকারটাই আবার
    const home = await act(PH, 'rx_pharmacy_home');
    const on = home.online || home.pharmacy?.online || {};
    await act(PH, 'rx_pharmacy_online_set', { enabled: on.enabled ?? true, radiusKm: on.radiusKm ?? 3, fee: on.fee ?? '৩০ টাকা', note: on.note ?? '' });
    // ওষুধ খোঁজার অনুরোধ বন্ধ → চালু
    await act(PH, 'pharmacy_find_setting', { off: true });
    if ((await act(PH, 'med_find_requests')).findOff !== true) throw new Error('findOff চালু হয়নি');
    await act(PH, 'pharmacy_find_setting', { off: false });
    if ((await act(PH, 'med_find_requests')).findOff !== false) throw new Error('findOff বন্ধ হয়নি');
    // রোগী দোকানের কোড দিয়ে দোকান খোঁজেন, ফোন করেন
    const shop = (await act(PH, 'pharmacy_me')).pharmacy;
    const code = shop.shopCode || shop.code || 'DEMQKP';
    const found = await act(P, 'rx_shop_find', { code });
    if (!JSON.stringify(found).includes(shop.name)) throw new Error(`rx_shop_find(${code}) দোকান পায়নি`);
    await act(P, 'pharmacy_called', { id: shop.id || PH.uid });
  });

  // ---------------- প্রশ্নোত্তর আর টিপস: বদল ও মোছা ----------------
  await J.sc('MORE-health', 'প্রশ্ন/উত্তর/টিপস: লেখক বদলান, অ্যাডমিন/লেখক মোছেন', async () => {
    const q = await act(P, 'ask', { text: 'ই২ই প্রশ্ন: বদলানো আর মোছার পরীক্ষা — জ্বরে কী খাব?' });
    const qid = q.id || q.question?.id;
    await act(DOC, 'answer', { id: qid, text: 'ই২ই উত্তর: পানি বেশি খান।' });
    const qa = await act(P, 'question', { id: qid });
    const ans = (qa.answers || qa.question?.answers || []).find((a) => /ই২ই উত্তর/.test(a.text));
    if (!ans) throw new Error('উত্তর পাওয়া যায়নি');
    await act(DOC, 'edit_answer', { id: qid, answerId: ans.id, text: 'ই২ই উত্তর (বদল): পানি আর স্যালাইন।' });
    const qa2 = await act(P, 'question', { id: qid });
    if (!JSON.stringify(qa2).includes('স্যালাইন')) throw new Error('edit_answer দেখা যায় না');
    await act(DOC, 'delete_answer', { id: qid, answerId: ans.id });
    await act(P, 'delete_question', { id: qid });
    await fails(P, 'question', { id: qid });
    // টিপস: যাচাই করা ডাক্তার লেখেন, বদলান, অ্যাডমিন মোছেন
    const tp = await act(DOC, 'post_tip', { title: 'ই২ই টিপস', body: 'ই২ই: হাত ধুয়ে খাবার খান।' });
    const tid = tp.id || tp.tip?.id;
    await act(DOC, 'edit_tip', { id: tid, title: 'ই২ই টিপস (বদল)', body: 'ই২ই: সাবান দিয়ে ২০ সেকেন্ড হাত ধুন।' });
    if (!JSON.stringify(await act(P, 'tips')).includes('ই২ই টিপস (বদল)')) throw new Error('edit_tip দেখা যায় না');
    await act(O, 'delete_tip', { id: tid });
    if (JSON.stringify(await act(P, 'tips')).includes('ই২ই টিপস')) throw new Error('delete_tip-এর পরেও আছে');
  });

  // ---------------- রক্ত: রিপোর্ট, ভুল করে বন্ধ → আবার খোলা ----------------
  await J.sc('MORE-blood', 'রক্তের রিকোয়েস্ট: রিপোর্ট (একবারই), বন্ধ করে ১ ঘণ্টার মধ্যে আবার খোলা', async () => {
    const r = await act(P, 'request_create', {
      ...AT, patientGroup: 'AB-', bags: 1, hospital: 'ই২ই খোলা-বন্ধ হাসপাতাল', placeName: GZP, neededByMillis: Date.now() + 86400000,
      requesterPhone: '01200001101', requesterName: 'ডেমো রোগী রহিম', note: 'ই২ই', urgent: false,
    }).catch(async (e) => {
      // খোলা রিকোয়েস্টের সীমা (২টা) — আগের যাত্রার খোলা থাকলে সেটাই ব্যবহার
      if (!(e instanceof ApiError) || e.status >= 500) throw e;
      return { id: (await act(P, 'blood_requests_mine')).items.find((x) => x.status === 'open')?.id };
    });
    const id = r.id;
    await act(D, 'report_request', { requestId: id, reason: 'ই২ই: ভুয়া মনে হচ্ছে' });
    await fails(D, 'report_request', { requestId: id, reason: 'আবার' }, 409);
    await post(P, '/api/close-request', { requestId: id, notNeeded: true });
    eq((await act(P, 'blood_request_get', { requestId: id })).request?.status ?? (await act(P, 'blood_request_get', { id })).request?.status, 'closed', 'বন্ধের পরে অবস্থা');
    await act(P, 'request_reopen', { requestId: id });
    const st = (await act(P, 'blood_requests_mine')).items.find((x) => x.id === id)?.status;
    eq(st, 'open', 'request_reopen-এর পরে অবস্থা');
    await act(P, 'request_delete', { requestId: id });
  });

  // ---------------- প্যাথলজি: স্বাভাবিক মাত্রা, বাসা থেকে নমুনা বাতিল ----------------
  await J.sc('MORE-lab', 'প্যাথলজি: নিজের স্বাভাবিক মাত্রা; রোগী বাসা থেকে নমুনার অনুরোধ নিশ্চিতের আগে বাতিল (ঠিকানা মোছে)', async () => {
    await act(LAB, 'lab_ranges_set', { ranges: [{ code: 'hba1c', key: 'hba1c', low: 4.2, high: 5.8 }] });
    // রোগীর এমন প্রেসক্রিপশন যাতে পরীক্ষা আছে আর এই ল্যাবে এখনো পাঠানো হয়নি — নতুন একটা লিখিয়ে নেওয়া
    const rx = await act(DOC, 'rx_create', { appointmentId: await apptForRx(DOC, P, 'ডেমো রোগী রহিম', 31), diagnosis: 'ই২ই', tests: ['HbA1c'] });
    const lab = (await act(LAB, 'lab_me')).lab;
    await act(P, 'rx_send_lab', { rxId: rx.id, labId: lab.id || LAB.uid, home: { address: 'ই২ই বাতিলের বাসা', phone: '01200001101', day: 1, slot: 'morning', note: '' } });
    const order = (await act(P, 'rx_activity', { rxId: rx.id }).catch(() => null)) && (await act(LAB, 'lab_orders')).items.find((x) => x.rxId === rx.id);
    if (!order) throw new Error('ল্যাবের অর্ডার নেই');
    await act(P, 'lab_home_cancel', { id: order.id });
    const after = (await act(LAB, 'lab_orders')).items.find((x) => x.id === order.id);
    if (after && JSON.stringify(after).includes('ই২ই বাতিলের বাসা')) throw new Error('বাতিলের পরেও ল্যাব বাসার ঠিকানা দেখছে');
  });

  // ---------------- বাসায় সেবা: ফোন আর রেটিং ----------------
  await J.sc('MORE-homecare', 'বাসায় সেবা: নার্সকে ফোন (হিসাব), ৩০ দিনের মধ্যে রেটিং', async () => {
    const list = await act(P, 'home_providers', { type: 'nurse', district: GZP });
    const nurse = (list.items || []).find((x) => /নাসরিন/.test(x.name)) || (list.items || [])[0];
    if (!nurse) throw new Error('home_providers খালি');
    await act(P, 'home_called', { uid: nurse.uid || nurse.id });
    await act(P, 'home_rate', { uid: nurse.uid || nurse.id, rating: 5 });
  });

  // ---------------- ডাক্তারের সহকারী (সফল পথ) ----------------
  await J.sc('MORE-team', 'ডাক্তার: সহকারীর তালিকা বদলানো (একজন যোগ, তারপর আগের মতো)', async () => {
    const team = await act(DOC, 'rx_team');
    const emails = (team.emails || team.items || []).map((x) => (typeof x === 'string' ? x : x.email));
    // ডেমো ডাক্তারের সহকারী শুধু ডেমো অ্যাকাউন্ট হতে পারে (ডেমো আর আসল আলাদা) — ডেমো কেয়ারগিভারকে যোগ
    const CG = await demo('caregiver');
    await act(DOC, 'rx_team_set', { emails: [...emails, 'caregiver@lifolo-demo.test'] });
    const t2 = await act(DOC, 'rx_team');
    if (!JSON.stringify(t2).includes('caregiver@lifolo-demo.test')) throw new Error('rx_team_set-এর পরে নেই');
    if (!(await act(CG, 'rx_assisting')).items?.length) throw new Error('নতুন সহকারী নিজের "সহায়তা" দেখেন না');
    await act(DOC, 'rx_team_set', { emails });
  });

  // ---------------- ফোন যাচাই (SMS), Google ম্যাপের লিংক ----------------
  await J.sc('MORE-phone-maps', 'ফোন যাচাই: SMS চালু না থাকলে পরিষ্কার "বন্ধ" উত্তর; Google ম্যাপের লিংক থেকে জায়গা', async () => {
    const e = await fails(U, 'phone_send_code', {}, 400);
    if (!e.message) throw new Error('phone_send_code: বার্তা নেই');
    await fails(U, 'phone_verify_code', { code: '123456' });
    const p = await act(P, 'maps_link_resolve', { url: 'https://maps.google.com/?q=23.9990,90.4200' });
    if (Math.abs(p.lat - 23.999) > 0.001 || Math.abs(p.lng - 90.42) > 0.001) throw new Error(`maps_link_resolve: ${JSON.stringify(p)}`);
  });

  // ---------------- অ্যাডমিন প্যানেল থেকে লগআউট ----------------
  await J.sc('MORE-web-logout', 'অ্যাডমিন প্যানেল: লগআউট (web_logout) — কুকি যায়, আবার লগইন পাতা', async () => {
    const Pd = await J.ctxFor('desktop');
    J.cur = Pd;
    if (Pd.role && Pd.role !== 'guest' && Pd.role !== 'owner') await logout(Pd.page).catch(() => {});
    await adminLogin(Pd.page);
    Pd.role = 'owner';
    const page = Pd.page;
    await page.goto(`${BASE}/admin`);
    await settle(page, 500);
    await page.getByRole('button', { name: /লগআউট/ }).first().click();
    await page.waitForTimeout(200);
    const armed = page.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' });
    if (await armed.count()) await armed.first().click();
    await page.locator('input[type=email]').waitFor({ timeout: 10000 });
    await J.shot('অ্যাডমিন প্যানেল থেকে লগআউট', 'লগইন পাতা (ইমেইলের ঘর) — কুকির সেশন শেষ।');
    Pd.role = null;
    const r = await fetch(`${BASE}/api/community`, { method: 'POST', headers: { 'content-type': 'application/json', cookie: (await page.context().cookies()).map((c) => `${c.name}=${c.value}`).join('; ') }, body: JSON.stringify({ action: 'admin_overview' }) });
    if (r.status !== 401 && r.status !== 403) throw new Error(`লগআউটের পরেও কুকিতে admin_overview: ${r.status}`);
  });

  // ---------------- একদম শেষে: ডেমো মোছা ----------------
  await J.sc('MORE-demo-wipe', 'মালিক: ডেমো মোছা (admin_demo_wipe) — ডেমো লগইন বন্ধ, ডেমোর তথ্য নেই, আসল ব্যবহারকারী অক্ষত', async () => {
    let more = true;
    for (let i = 0; i < 30 && more; i++) more = !!(await act(O, 'admin_demo_wipe')).more;
    if (more) throw new Error('admin_demo_wipe ৩০ বারেও শেষ হয়নি');
    const roles = await post(null, '/api/auth/verify-code', { demoRoles: true });
    if (roles.active) throw new Error('ডেমো মোছার পরেও ডেমো তালিকা চালু');
    const r = await fetch(`${BASE}/api/auth/verify-code`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ demo: 'patient' }) });
    if (r.ok) throw new Error('ডেমো মোছার পরেও ডেমো রোগী হিসেবে লগইন হয়');
    const left = sql(`SELECT json_build_object('profiles', (SELECT count(*) FROM user_profiles WHERE uid LIKE 'demo-%'), 'requests', (SELECT count(*) FROM blood_requests WHERE requester_uid LIKE 'demo-%'), 'rx', (SELECT count(*) FROM prescriptions WHERE doctor_uid LIKE 'demo-%'))`);
    if (left && (left.profiles || left.requests || left.rx)) throw new Error(`ডেমোর তথ্য থেকে গেছে: ${JSON.stringify(left)}`);
    // আসল ব্যবহারকারী অক্ষত
    if (!(await act(U, 'profile_get')).profile) throw new Error('ডেমো মোছায় আসল ব্যবহারকারীর প্রোফাইল গেছে');
    const page = (await J.ctxFor('phone')).page;
    J.cur = await J.ctxFor('phone');
    await go(page, '/login');
    await settle(page, 600);
    await J.shot('ডেমো মোছার পরে লগইন পাতা', '‘ডেমো (পরীক্ষার জন্য)’ অংশ আর নেই — শুধু ইমেইল।');
    if (await page.getByText('ডেমো (পরীক্ষার জন্য)').count()) throw new Error('লগইন পাতায় এখনো ডেমো তালিকা');
  });
}
