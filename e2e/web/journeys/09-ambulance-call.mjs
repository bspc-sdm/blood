// "অ্যাম্বুলেন্স ডাকুন" — পুরো পথ, রোগী (ফোন) আর চালক (ডেস্কটপ) একসাথে, আসল সার্ভারে:
//   ambreq_count, ambreq_create, ambreq_driver, ambreq_accept (ভাড়ার প্রস্তাব), ambreq_agree (রাজি), ambreq_step (পৌঁছেছি/শেষ/
//   চালকের বাতিল), ambulance_rate, ambreq_cancel (রোগীর বাতিল), ambreq_decline (পারব না → ঢেউ বাড়ে), ambreq_budget (ভাড়া বাড়ানো),
//   ambulance_called + ambreq_byphone (ফোনে পাওয়া গাড়ি), ambreq_replace (রাজি হওয়া চালককে বাদ), ambreq_status।
// গন্তব্য "শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল" (লিখে) — ডেমো ভাড়ার তালিকায় আছে। ধরন "এসি" হলে প্রার্থী শুধু দুটো:
// চালকের ‘ডেমো জীবন অ্যাম্বুলেন্স (এসি)’ (৳১০০০) আর ‘ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস’ (৳৩০০০, ১২ ঘণ্টার বেশি আগে খালি
// জানানো) — তাই কে খবর পায় তা নিশ্চিত। একজন রোগী ঘণ্টায় ৫টা ডাকতে পারেন — তাই কিছু দৃশ্য ডেমো ডোনারকে দিয়ে।
import { pickQueueItem, tap, see, go, settle, until, vis, rowAct } from '../lib.mjs';

export const meta = { no: 9, slug: 'ambulance-call', titleBn: 'অ্যাম্বুলেন্স ডাকুন: ভাড়ার প্রস্তাব, রাজি, পৌঁছানো, বাতিল, ফোনে পাওয়া' };

const TAJ = 'শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল';
const AC_CAR = 'ডেমো জীবন অ্যাম্বুলেন্স (এসি)';
const MOMOTA = 'ডেমো মমতা অ্যাম্বুলেন্স';

/** রোগীর দিক: ঘর পূরণ করে পাঠানো → অপেক্ষার পাতা; ফেরত: অনুরোধের id */
async function callAmbulance(J, page, { type = 'এসি', budget = 'যেকোনো', unknown = false, note = '', expectCount = true } = {}) {
  await go(page, '/services/ambulance');
  // আগের কোনো অনুরোধ চালু থাকলে (আগের ব্যর্থ চালানো) — বাতিল করে নতুন করে
  const banner = page.locator('.amb-banner');
  if (await banner.count()) {
    await banner.first().click();
    await settle(page);
    await cancelAsPatient(page, 'আর দরকার নেই');
    await go(page, '/services/ambulance');
  }
  await page.getByTestId('amb-request').click();
  const d = page.getByRole('dialog').last();
  await d.getByText('গাড়ির ধরন').waitFor();
  await d.getByTestId('amb-type').getByRole('button', { name: type, exact: true }).click();
  // কোথা থেকে: এখনকার জায়গা (ব্রাউজারের GPS — গাজীপুর); তালিকা আগেই জায়গা পেয়ে থাকলে সেটাই বসানো থাকে
  if (!(await d.getByTestId('amb-gps-ok').count())) {
    await d.getByTestId('amb-gps').click();
    await d.getByRole('button', { name: 'অনুমতি দিন' }).click();
  }
  await d.getByTestId('amb-gps-ok').waitFor({ timeout: 10000 });
  await d.getByTestId('amb-pickup').fill('ডেমো রোগীর বাসা, শিববাড়ি মোড়ের পাশে');
  // কোথায় যাবেন: লিখে (ডেমো ভাড়ার তালিকার হাসপাতাল)
  await d.getByTestId('amb-dest-pick').click();
  await page.getByTestId('fac-other').click();
  await page.getByTestId('fac-other-input').fill(TAJ);
  await page.getByTestId('fac-other-ok').click();
  await d.getByText(TAJ).first().waitFor();
  await d.getByTestId('amb-budget').getByRole('button', { name: budget, exact: true }).click();
  if (unknown) await d.getByTestId('amb-budget').getByText('ভাড়া জানা নেই এমন গাড়িও').click();
  if (note) await d.getByLabel(/^নোট/).fill(note);
  const phone = d.getByLabel('আপনার মোবাইল (চালকের জন্য)');
  if (!(await phone.inputValue())) await phone.fill('01700001101');
  if (expectCount) await d.getByTestId('amb-count').filter({ hasText: /এই ভাড়ার মধ্যে .+টা খালি গাড়ি আছে/ }).waitFor({ timeout: 10000 });
  return d;
}

async function send(page, d) {
  await d.getByTestId('amb-send').click();
  await page.waitForURL(/\/services\/ambulance\/call\/[^/]+$/, { timeout: 15000 });
  await see(page, /অ্যাম্বুলেন্স খোঁজা হচ্ছে|প্রস্তাবের অপেক্ষা/);
  return page.url().split('/').pop();
}

async function cancelAsPatient(page, reason) {
  await page.getByRole('button', { name: 'অনুরোধ বাতিল করুন' }).click();
  const r = page.getByRole('dialog').last();
  await r.getByRole('button', { name: reason, exact: true }).click();
  await r.getByRole('button', { name: 'বাতিল করুন', exact: true }).click();
  await see(page, 'আপনি অনুরোধ বাতিল করেছেন', 10000);
}

/** চালকের দিক: আমার কাজ › অ্যাম্বুলেন্স — "ডাক" অংশের কার্ড (নতুনটা আগে আসা পর্যন্ত অপেক্ষা) */
async function driverCall(page, pickup) {
  await go(page, '/work/ambulance');
  const card = page.getByTestId('amb-call').filter({ hasText: pickup });
  // ডাক এখন নতুন / চলছে / শেষ ভাগে, আর ডেস্কটপে তালিকা-বিস্তারিত (বাঁয়ে সারি, ডানে বাছা ডাক) — যে ভাগে আছে সেখানে বাছা
  const segs = page.getByTestId('queue-segs').first().getByRole('tab');
  const t0 = Date.now();
  while (Date.now() - t0 < 15000) {
    if (await card.count()) return card.first();
    for (let i = 0; i < (await segs.count()); i++) {
      await segs.nth(i).click().catch(() => {});
      await settle(page, 150);
      await pickQueueItem(page, pickup);
      if (await card.count()) return card.first();
    }
    await page.waitForTimeout(1000);
  }
  await card.first().waitFor({ timeout: 2000 });
  return card.first();
}

/** রোগীর অপেক্ষার পাতায় অবস্থা (data-status) আসা পর্যন্ত — পাতা নিজে ৪/১০ সেকেন্ড পরপর দেখে */
async function waitStatus(page, status, timeout = 20000) {
  await until(page, (s) => document.querySelector('.amb-wait')?.getAttribute('data-status') === s, status, timeout);
  await settle(page);
}

export async function run(J) {
  let patient;
  let driver;
  let reqId;
  const carName = AC_CAR;

  await J.sc('AMB-01', 'রোগী: অ্যাম্বুলেন্স ডাকার ঘর — ধরন, জায়গা, গন্তব্য, ভাড়া, কয়টা গাড়ি', async () => {
    patient = await J.as('patient');
    await go(patient, '/services/ambulance');
    await J.shot('সেবা › অ্যাম্বুলেন্স — উপরে ‘অ্যাম্বুলেন্স ডাকুন’', 'বড় বোতাম ‘অ্যাম্বুলেন্স ডাকুন — কাছের খালি গাড়িগুলোতে একসাথে’, পাশে ‘ফোন’।');
    const d = await callAmbulance(J, patient, { note: 'রোগী হাঁটতে পারেন না' });
    await J.shot('ডাকার ঘর ভরা', `এখনকার জায়গা নেওয়া ✓, গন্তব্য ‘${TAJ}’, ভাড়া ‘যেকোনো’, ‘এই ভাড়ার মধ্যে …টা খালি গাড়ি আছে’।`);
    await d.getByTestId('amb-count').filter({ hasText: 'এই ভাড়ার মধ্যে ২টা খালি গাড়ি আছে' }).waitFor({ timeout: 10000 });
    // ভাড়ার সীমা ৳১০০০ — চালকের এসি গাড়ি (৳১০০০) থাকে, আইসিইউ (৳৩০০০) বাদ
    await d.getByTestId('amb-budget').getByRole('button', { name: '৳১,০০০', exact: true }).click();
    await d.getByTestId('amb-count').filter({ hasText: 'এই ভাড়ার মধ্যে ১টা খালি গাড়ি আছে' }).waitFor({ timeout: 10000 });
    await J.shot('ভাড়া সর্বোচ্চ ৳১,০০০', '‘এই ভাড়ার মধ্যে ১টা খালি গাড়ি আছে’ (ambreq_count) — যেকোনো ভাড়ায় ২টা।');
    await d.getByTestId('amb-budget').getByRole('button', { name: 'যেকোনো', exact: true }).click();
    reqId = await send(patient, d);
    await J.shot('অপেক্ষার পাতা', '‘অ্যাম্বুলেন্স খোঁজা হচ্ছে…’, ‘আপনার ভাড়ার মধ্যে কাছের …টা অ্যাম্বুলেন্সকে জানানো হয়েছে’, অপেক্ষার ঘড়ি, ‘অনুরোধ বাতিল করুন’।', { full: true });
    await see(patient, 'রোগী হাঁটতে পারেন না');
  });

  await J.sc('AMB-02', 'চালক: ডাক দেখা আর ভাড়া বলে "আসছি"', async () => {
    driver = await J.as('ambulance', 'desktop');
    const card = await driverCall(driver, 'শিববাড়ি মোড়ের পাশে');
    await J.shot('চালক: ‘ডাক’ — অ্যাম্বুলেন্স দরকার', `কার্ডে দূরত্ব, এলাকা, ঠিকানা, নোট ‘রোগী হাঁটতে পারেন না’, ‘যাবেন: ${TAJ}’, ভাড়ার তালিকা থেকে আনুমানিক ভাড়া; রোগীর নাম/ফোন এখনো নেই।`);
    if (await card.getByText('ডেমো রোগী রহিম').count()) throw new Error('রাজির আগেই চালক রোগীর নাম দেখছেন');

    await card.getByTestId('amb-fare-input').fill('1200');
    await card.getByTestId('amb-accept').click();
    await card.getByTestId('amb-quote-wait').waitFor({ timeout: 10000 });
    await J.shot('চালক: ভাড়া ৳১,২০০ বলা হলো', '‘রোগীর উত্তরের অপেক্ষা…’ — ‘আপনার ভাড়া ৳১,২০০ — রোগী "রাজি" চাপলে তবেই নিশ্চিত।’');
  });

  await J.sc('AMB-03', 'রোগী: ভাড়ার প্রস্তাবে "রাজি"', async () => {
    patient = await J.as('patient');
    await go(patient, `/services/ambulance/call/${reqId}`);
    const quote = patient.getByTestId('amb-quote').filter({ hasText: '৳১,২০০' });
    await quote.first().waitFor({ timeout: 15000 });
    await J.shot('ভাড়ার প্রস্তাব — একটা বেছে নিন', '‘ভাড়ার প্রস্তাব’: চালকের গাড়ি ৳১,২০০, ‘থাকবে আর …’, ‘রাজি’।', { full: true });
    await quote.first().getByText(AC_CAR).waitFor();
    await quote.first().getByTestId('amb-agree').click();
    await waitStatus(patient, 'accepted');
    await see(patient, `${carName}`);
    const fare = await patient.getByTestId('amb-agreed-fare').textContent();
    if (!/৳১,২০০/.test(fare)) throw new Error(`ঠিক হওয়া ভাড়া ভুল: ${fare}`);
    await J.shot('রাজি — গাড়ি আসছে', `‘আসছে’ — ${carName}, ‘ঠিক হওয়া ভাড়া ৳১,২০০’, ‘চালককে ফোন …’।`, { full: true });
    // তালিকায় ফিরলে উপরে চালু অনুরোধের ব্যানার
    await go(patient, '/services/ambulance');
    await see(patient, `${carName} আসছে`);
    await J.shot('তালিকার উপরে ব্যানার', `‘${carName} আসছে — দেখতে চাপুন’।`);
  });

  await J.sc('AMB-04', 'চালক: রোগীর নাম-ফোন, "পৌঁছেছি", "শেষ"', async () => {
    driver = await J.as('ambulance', 'desktop');
    const card = await driverCall(driver, 'শিববাড়ি মোড়ের পাশে');
    await card.getByText('আপনি যাচ্ছেন').waitFor({ timeout: 15000 });
    await see(driver, 'ডেমো রোগী রহিম');
    const agreed = await card.getByTestId('amb-agreed-fare').textContent();
    if (!/৳১,২০০/.test(agreed)) throw new Error(`চালকের দিকে ভাড়া ভুল: ${agreed}`);
    await J.shot('চালক: আপনি যাচ্ছেন', '‘আপনি যাচ্ছেন’, ‘ঠিক হওয়া ভাড়া ৳১,২০০’, রোগীর নাম ‘ডেমো রোগী রহিম’, ফোন, ‘ম্যাপে খুলুন’, মূল বোতাম ‘পৌঁছেছি’ (‘শেষ’, ‘বাতিল’ — ‘আরও’-তে)।');
    await card.getByRole('button', { name: 'পৌঁছেছি', exact: true }).click();
    await card.getByText('আপনি পৌঁছেছেন').waitFor({ timeout: 10000 });
    patient = await J.as('patient');
    await go(patient, `/services/ambulance/call/${reqId}`);
    await waitStatus(patient, 'arrived');
    await J.shot('রোগী: পৌঁছেছে', `‘পৌঁছেছে’ — ${carName}।`);
    driver = await J.as('ambulance', 'desktop');
    const card2 = await driverCall(driver, 'শিববাড়ি মোড়ের পাশে');
    await card2.getByRole('button', { name: 'শেষ', exact: true }).click();
    await see(driver, 'ট্রিপ শেষ। আপনি আবার খালি।');
    await J.shot('চালক: ট্রিপ শেষ', '‘ট্রিপ শেষ। আপনি আবার খালি।’ — ডাকের কার্ড চলে যায়।');
  });

  await J.sc('AMB-05', 'রোগী: ট্রিপ শেষ — অ্যাম্বুলেন্সকে রেটিং', async () => {
    patient = await J.as('patient');
    await go(patient, `/services/ambulance/call/${reqId}`);
    await waitStatus(patient, 'done');
    await J.shot('ট্রিপ শেষ', `‘ট্রিপ শেষ’ — ${carName}, ‘অ্যাম্বুলেন্সকে রেটিং দিন’।`);
    await tap(patient, 'অ্যাম্বুলেন্সকে রেটিং দিন');
    const d = patient.getByRole('dialog').last();
    await d.getByRole('radio', { name: '৫' }).click();
    await d.getByText(/বলা ভাড়ার চেয়ে বেশি নিয়েছে \(৳১,২০০\)/).waitFor();
    await d.getByLabel('মন্তব্য (ঐচ্ছিক)').fill('ডেমো: ঠিক সময়ে এসেছিল');
    await J.shot('রেটিং — ৫ তারা', '৫ তারা, ‘বলা ভাড়ার চেয়ে বেশি নিয়েছে (৳১,২০০)’ বাছা যায়, মন্তব্য।');
    await d.getByRole('button', { name: 'জমা দিন' }).click();
    await see(patient, 'রেটিং দেওয়ার জন্য ধন্যবাদ');
    await J.shot('রেটিং জমা', '‘রেটিং দেওয়ার জন্য ধন্যবাদ!’');
  });

  await J.sc('AMB-06', 'রোগী: অনুরোধ বাতিল (কারণসহ) — চালকের তালিকা থেকে চলে যায়', async () => {
    patient = await J.as('patient');
    const d = await callAmbulance(J, patient);
    await d.getByTestId('amb-pickup').fill('ডেমো বাতিলের ঠিকানা, জয়দেবপুর');
    const id = await send(patient, d);
    driver = await J.as('ambulance', 'desktop');
    await driverCall(driver, 'ডেমো বাতিলের ঠিকানা');
    patient = await J.as('patient');
    await go(patient, `/services/ambulance/call/${id}`);
    await cancelAsPatient(patient, 'আর দরকার নেই');
    await see(patient, 'আর দরকার নেই');
    await J.shot('রোগী বাতিল করেছেন', '‘আপনি অনুরোধ বাতিল করেছেন’ — কারণ ‘আর দরকার নেই’; ‘তালিকায় ফিরুন’।', { full: true });
    driver = await J.as('ambulance', 'desktop');
    await go(driver, '/work/ambulance');
    await settle(driver, 800);
    if (await driver.getByTestId('amb-call').filter({ hasText: 'ডেমো বাতিলের ঠিকানা' }).count()) throw new Error('বাতিলের পরেও চালক ডাকটা দেখছেন');
    await J.shot('চালক: বাতিল হওয়া ডাক নেই', 'বাতিল হওয়া অনুরোধ চালকের ‘ডাক’-এ আর নেই।');
  });

  await J.sc('AMB-07', 'ডোনার (রোগী হিসেবে): চালক "পারব না" → ঢেউ বাড়ে → ভাড়া বাড়ানো', async () => {
    const p = await J.as('donor');
    const d = await callAmbulance(J, p, { budget: '৳১,০০০' });
    await d.getByTestId('amb-pickup').fill('ডেমো ডোনারের বাসা, চান্দনা');
    await d.getByTestId('amb-count').filter({ hasText: 'এই ভাড়ার মধ্যে ১টা খালি গাড়ি আছে' }).waitFor({ timeout: 10000 });
    const id = await send(p, d);
    await see(p, 'আপনার ভাড়ার মধ্যে কাছের ১টা অ্যাম্বুলেন্সকে জানানো হয়েছে');
    await J.shot('ডোনার: ৳১,০০০-এর মধ্যে ১টা গাড়িকে জানানো', '‘আপনার ভাড়ার মধ্যে কাছের ১টা অ্যাম্বুলেন্সকে জানানো হয়েছে’ (শুধু চালকের এসি গাড়ি)।');
    driver = await J.as('ambulance', 'desktop');
    const card = await driverCall(driver, 'ডেমো ডোনারের বাসা');
    // "পারব না" এখন কার্ডের "আরও"-তে (আমার কাজ CONTRACT §২ — একটাই মূল বোতাম "আসছি")
    await rowAct(driver, card, 'পারব না');
    await settle(driver, 600);
    if (await driver.getByTestId('amb-call').filter({ hasText: 'ডেমো ডোনারের বাসা' }).count()) throw new Error('"পারব না"-র পরেও ডাকটা চালকের তালিকায়');
    await J.shot('চালক: পারব না', 'ডাকটা চালকের তালিকা থেকে চলে যায় (ambreq_decline)।');
    // সবাই না বললে ঢেউ আগেই বাড়ে (wave 2) — এই ভাড়ায় আর কেউ নেই, তাই "ভাড়া বাড়ান"
    const p2 = await J.as('donor');
    await go(p2, `/services/ambulance/call/${id}`);
    const raise = p2.getByTestId('amb-raise');
    await raise.waitFor({ timeout: 15000 });
    await J.shot('এখনো কেউ ভাড়া বলেননি — ভাড়া বাড়ান', '‘এখনো কেউ ভাড়া বলেননি। আরও গাড়ি পেতে ভাড়া বাড়ান:’ — ‘৳… পর্যন্ত বাড়ান’, ‘যেকোনো ভাড়া’।', { full: true });
    const label = (await raise.textContent()).trim();
    if (!/^৳৫,০০০ পর্যন্ত বাড়ান$/.test(label)) throw new Error(`ভাড়া বাড়ানোর বোতাম: ${label} (আশা ছিল ৳৫,০০০ — আইসিইউ ৳৩০০০)`);
    await raise.click();
    await see(p2, 'আরও ১টা গাড়িকে জানানো হয়েছে', 10000);
    await J.shot('ভাড়া বাড়ানো হলো', '‘আরও …টা গাড়িকে জানানো হয়েছে’ (ambreq_budget); ‘ভাড়া সর্বোচ্চ’ নতুন অঙ্ক।', { full: true });
    await cancelAsPatient(p2, 'অন্য গাড়ি পেয়েছি');
  });

  await J.sc('AMB-08', 'ডোনার: তালিকার গাড়িতে ফোন → "ফোনে কোনো গাড়ি পেয়েছেন?" → হ্যাঁ', async () => {
    const p = await J.as('donor');
    const d = await callAmbulance(J, p);
    await d.getByTestId('amb-pickup').fill('ডেমো ফোনের ঠিকানা, ভোগড়া');
    const id = await send(p, d);
    // ৬০ সেকেন্ডে কেউ না নিলে ঢেউ বাড়ে (wave 2) আর "অথবা এখনই ফোন করুন:" — কাছের খালি গাড়ি (সময় ধরে বাড়া — ambreq advance)
    const row = p.locator('.amb-fallback').filter({ hasText: AC_CAR }).first();
    await row.waitFor({ timeout: 90000 });
    await see(p, 'অথবা এখনই ফোন করুন:');
    const name = AC_CAR;
    await row.getByRole('link', { name: 'ফোন করুন' }).click({ noWaitAfter: true });
    await settle(p, 600);
    await J.shot('অপেক্ষার সময় সরাসরি ফোন', `‘অথবা এখনই ফোন করুন:’ থেকে ‘${name}’-এ ফোন (অনুরোধে ফোনটা ওঠে)।`, { full: true });
    await go(p, `/services/ambulance/call/${id}`);
    const ask = p.getByRole('dialog').filter({ hasText: 'ফোনে কোনো গাড়ি পেয়েছেন?' });
    if (!(await ask.count())) await p.getByRole('button', { name: 'ফোনে কোনো গাড়ি পেয়েছেন?' }).click();
    const dlg = p.getByRole('dialog').last();
    await dlg.getByRole('button', { name: `হ্যাঁ — ${name}` }).waitFor();
    await J.shot('ফোনে কোনো গাড়ি পেয়েছেন?', `‘হ্যাঁ — ${name}’ আর ‘না, খুঁজতে থাকুন’।`);
    await dlg.getByRole('button', { name: `হ্যাঁ — ${name}` }).click();
    await waitStatus(p, 'byPhone');
    await J.shot('ফোনে গাড়ি পেয়েছেন', `‘ফোনে গাড়ি পেয়েছেন’ — ${name}; অনুরোধ বন্ধ, অন্য চালকরা খবর পান।`, { full: true });
  });

  await J.sc('AMB-09', 'রোগী: রাজির পরে "ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল"', async () => {
    patient = await J.as('patient');
    // যেকোনো ধরন + ভাড়া না জানা গাড়িও: চালক (তাঁর কাছের গাড়ি) আর মমতা দুজনেই খবর পান, দুটোই "এখনই ফোন করুন"-এ
    const d = await callAmbulance(J, patient, { type: 'যেকোনো', unknown: true });
    await d.getByTestId('amb-pickup').fill('ডেমো বদলের ঠিকানা, শিববাড়ি');
    const id = await send(patient, d);
    // অনুরোধ চলার সময় তালিকা থেকে মমতায় ফোন — ফোনটা অনুরোধে ওঠে (রাজির পরে "অন্য গাড়ি" বাছার জন্য)
    const other = MOMOTA;
    await go(patient, '/services/ambulance');
    await see(patient, 'আপনার অ্যাম্বুলেন্সের অনুরোধ চলছে');
    await patient.locator('article').filter({ hasText: MOMOTA }).first().click();
    await settle(patient);
    // এক ছাঁচের বিস্তারিত: নিচের বারে কলের আইকন (ফোন কল)
    await vis(patient.getByTestId('detail-bar').locator('a[href^="tel:"]')).first().click({ noWaitAfter: true });
    await settle(patient, 400);
    await J.shot('অনুরোধ চলার সময় তালিকা থেকে ফোন', `তালিকার উপরে ‘আপনার অ্যাম্বুলেন্সের অনুরোধ চলছে’; ‘${MOMOTA}’-এ ফোন — অনুরোধে ওঠে।`);
    driver = await J.as('ambulance', 'desktop');
    const card = await driverCall(driver, 'ডেমো বদলের ঠিকানা');
    await card.getByTestId('amb-fare-input').fill('1000');
    await card.getByTestId('amb-accept').click();
    await card.getByTestId('amb-quote-wait').waitFor({ timeout: 10000 });
    patient = await J.as('patient');
    await go(patient, `/services/ambulance/call/${id}`);
    // ফোনের পরে পাতায় ফিরলে একবার জিজ্ঞেস — "না, খুঁজতে থাকুন"
    const ask = patient.getByRole('dialog').filter({ hasText: 'ফোনে কোনো গাড়ি পেয়েছেন?' });
    if (await ask.count()) await ask.getByRole('button', { name: 'না, খুঁজতে থাকুন' }).click();
    await patient.getByTestId('amb-agree').first().click();
    await waitStatus(patient, 'accepted');
    await see(patient, 'ফোনে অন্য গাড়িও ঠিক করেছেন?');
    await J.shot('রাজির পরে — ফোনে অন্য গাড়িও?', '‘… আসছে। ফোনে অন্য গাড়িও ঠিক করেছেন?’ — ‘ঠিক আছে’ আর ‘ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল’।', { full: true });
    await tap(patient, 'ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল');
    const dlg = patient.getByRole('dialog').last();
    await dlg.getByRole('button', { name: `হ্যাঁ — ${other}` }).click();
    await waitStatus(patient, 'byPhone');
    await J.shot('অন্য গাড়ি — আগেরজনকে বাদ', `‘ফোনে গাড়ি পেয়েছেন’ — ${other}; রাজি হওয়া চালক মুক্ত (ambreq_replace)।`, { full: true });
    driver = await J.as('ambulance', 'desktop');
    await go(driver, '/work/ambulance');
    await settle(driver, 800);
    if (await driver.getByTestId('amb-call').filter({ hasText: 'ডেমো বদলের ঠিকানা' }).count()) throw new Error('বাদ পড়ার পরেও চালকের কাছে ডাকটা');
  });

  await J.sc('AMB-10', 'চালক: রাজির পরে বাতিল (কারণসহ) — রোগী জানেন', async () => {
    const p = await J.as('donor');
    const d = await callAmbulance(J, p);
    await d.getByTestId('amb-pickup').fill('ডেমো চালক-বাতিলের ঠিকানা');
    const id = await send(p, d);
    driver = await J.as('ambulance', 'desktop');
    let card = await driverCall(driver, 'ডেমো চালক-বাতিলের ঠিকানা');
    await card.getByTestId('amb-fare-input').fill('900');
    await card.getByTestId('amb-accept').click();
    await card.getByTestId('amb-quote-wait').waitFor({ timeout: 10000 });
    const p2 = await J.as('donor');
    await go(p2, `/services/ambulance/call/${id}`);
    await p2.getByTestId('amb-agree').first().click();
    await waitStatus(p2, 'accepted');
    driver = await J.as('ambulance', 'desktop');
    card = await driverCall(driver, 'ডেমো চালক-বাতিলের ঠিকানা');
    await card.getByText('আপনি যাচ্ছেন').waitFor({ timeout: 15000 });
    await rowAct(driver, card, 'বাতিল');
    const r = driver.getByRole('dialog').last();
    await r.getByRole('button', { name: 'গাড়িতে সমস্যা', exact: true }).click();
    await J.shot('চালক: বাতিলের কারণ', '‘কেন বাতিল করছেন?’ — ‘গাড়িতে সমস্যা’ বাছা।');
    await r.getByRole('button', { name: 'বাতিল করুন', exact: true }).click();
    await settle(driver, 600);
    const p3 = await J.as('donor');
    await go(p3, `/services/ambulance/call/${id}`);
    await waitStatus(p3, 'cancelled');
    await see(p3, 'বাতিল করেছেন');
    await see(p3, 'গাড়িতে সমস্যা');
    await J.shot('ডোনার: চালক বাতিল করেছেন', '‘… বাতিল করেছেন’ — কারণ ‘গাড়িতে সমস্যা’; নিচে কাছের খালি গাড়িতে ফোনের বোতাম।', { full: true });
  });
}
