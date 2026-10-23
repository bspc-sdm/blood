// সেবা (গাইড services.json): অ্যাম্বুলেন্স/ফার্মেসি/প্যাথলজি/নার্সের তালিকা, ওষুধ খোঁজা, অনলাইন অর্ডার, দোকানের হিসাব-খাতা,
// প্যাথলজির দাম আর বাসা থেকে নমুনা, রেটিং, রিপোর্ট, মালিকের অনুমোদন
import { tap, see, go, settle, logout, pharmacyWork, labWorkTab, orderRow, inStore, copyIfThere, rxListAll } from '../lib.mjs';
import { adminAct, posPicker, posNoPrint, posSave } from '../lib.mjs';
import { BASE } from '../stack.mjs';
import { pickDistrict } from './02-blood.mjs';
import { adminLogin } from './00-seed.mjs';

export const meta = { no: 5, slug: 'services', titleBn: 'সেবা: ফার্মেসি, প্যাথলজি, নার্স, দোকানের হিসাব' };

async function area(page) {
  // সেবার তালিকা (এক ছাঁচ): এলাকার চিপ ("এলাকা বদলান: …") বা খালি অবস্থার "এলাকা বদলান"; বাসায় সেবা: "এলাকা বেছে নিন"
  const choose = page.getByRole('button', { name: /এলাকা বেছে নিন/ }).first();
  if (await page.getByTestId('area-chip').count()) {
    if (!(await page.getByTestId('listing-card').count()) && (await page.getByTestId('listing-empty').count())) {
      await page.getByTestId('area-chip').click();
      await pickDistrict(page);
    }
    return;
  }
  if (await choose.count()) {
    await choose.click();
    await pickDistrict(page);
  }
}
async function filter(page, label) {
  await tap(page, 'ফিল্টার');
  const d = page.getByRole('dialog').last();
  await d.getByText(label, { exact: true }).first().click();
  const show = d.getByRole('button', { name: 'দেখান' });
  if (await show.count()) await show.click();
  else await page.keyboard.press('Escape');
  await settle(page);
}
async function clearFilters(page) {
  for (let i = 0; i < 3; i++) {
    const x = page.getByRole('button', { name: /সরান/ });
    if (!(await x.count())) break;
    await x.first().click();
    await settle(page);
  }
}
const card = (page, name) => page.locator('article').filter({ hasText: name }).first();
/** সেবার তালিকার ছাঁকনির চিপ (এক লাইন) — চাপলে চালু/বন্ধ */
async function chip(page, label) {
  await page.getByTestId('filter-chips').getByRole('button', { name: label, exact: true }).first().click();
  await settle(page);
}
/** চালু চিপগুলো মুছুন */
async function clearChips(page) {
  const x = page.getByTestId('filter-clear');
  if (await x.count()) {
    await x.first().click();
    await settle(page);
  }
}

/** আমার কাজ › ফার্মেসি › প্রেসক্রিপশন ও অর্ডার › ট্যাব */
async function shop(page, tab) {
  await pharmacyWork(page, tab);
}
async function labWork(page, tab) {
  await labWorkTab(page, tab);
}
/** নির্দিষ্ট প্রেসক্রিপশন — "সব" ট্যাবে (শেষ বা কোর্স পেরোনোগুলো "চলমান"-এ থাকে না) */
async function openRx(page, meds) {
  await rxListAll(page);
  await page.locator('article, [role=button]').filter({ hasText: meds }).filter({ hasNotText: 'বাতিল' }).first().click();
  await settle(page);
}
async function owner(J) {
  const P = await J.ctxFor('desktop');
  J.cur = P;
  if (P.role !== 'owner') {
    if (P.role && P.role !== 'guest') await logout(P.page).catch(() => {});
    await adminLogin(P.page);
    P.role = 'owner';
  }
  await P.page.goto(`${BASE}/admin`);
  await settle(P.page);
  return P.page;
}
async function adminTab(page, menu, tab) {
  await page.getByText(menu, { exact: true }).first().click();
  await settle(page);
  if (tab) {
    await page.getByRole('button', { name: tab, exact: true }).or(page.getByRole('tab', { name: tab, exact: true })).first().click();
    await settle(page);
  }
}
async function approveFirst(page, name) {
  const row = page.locator('tr').filter({ hasText: name }).first();
  await row.getByText(/যাচাই করে (অনুমোদন|মঞ্জুর)/).first().click();
  await settle(page);
}
async function tickAll(page) {
  const boxes = page.locator('input[type=checkbox]:visible');
  // যাচাইয়ের টিকের তালিকা আবেদন লোড হওয়ার পরে আসে — সার্ভার ধীর হলে (Spring-এর প্রথম ডাক) আগেই গুনলে ০টা
  await boxes.first().waitFor({ timeout: 8000 }).catch(() => {});
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check({ force: true }).catch(() => {});
  await settle(page, 200);
}

export async function run(J) {
  let page;

  // ---------------- অতিথি: তালিকা ----------------
  await J.sc('S-01', 'অ্যাম্বুলেন্সের তালিকা দেখা (অতিথি)', async () => {
    page = await J.as('guest');
    await go(page, '/services/ambulance');
    await area(page);
    await J.shot('অতিথি: অ্যাম্বুলেন্সের তালিকা', 'জীবন (এসি), জীবন মিনি, শেষ যাত্রা ফ্রিজার ভ্যান, মমতা — ‘খালি আছে’; আইসিইউ সার্ভিস ‘ফোনে জেনে নিন’; বড় বাটন নেই; ‘রেটিং দিন’ নেই।', { full: true });
  });

  await J.sc('S-02', 'অ্যাম্বুলেন্সের ধরন ফিল্টার আর ফোন', async () => {
    await chip(page, 'ফ্রিজার ভ্যান');
    await J.shot('ফিল্টার: ফ্রিজার ভ্যান', 'শুধু ‘ডেমো শেষ যাত্রা ফ্রিজার ভ্যান’।', { full: true });
    await clearChips(page);
    await card(page, 'ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস').click();
    await settle(page);
    const copied = await copyIfThere(page);
    await J.shot(copied ? 'আইসিইউ সার্ভিস — নম্বর কপি' : 'আইসিইউ সার্ভিস — নম্বর', 'বিস্তারিত; নম্বর ০১২০০০০০১৭৪; ‘কপি হয়েছে’।');
  });

  await J.sc('S-03', 'ফার্মেসির তালিকা', async () => {
    await go(page, '/services/pharmacy');
    await inStore(page);
    await area(page);
    await J.shot('অতিথি: ফার্মেসির তালিকা', 'ডেমো লাইফ (খোলা, ১০% ছাড়, ডেলিভারি ৩০ টাকা), ডেমো ২৪ ঘণ্টা, নিউ মডেল, সেবা ড্রাগ হাউস (‘বন্ধ থাকবে … পর্যন্ত’), কালিয়াকৈর মেডিসিন কর্নার; আরোগ্য নেই।', { full: true });
    await card(page, 'ডেমো লাইফ ফার্মেসি').click();
    await settle(page);
    await J.shot('ডেমো লাইফ ফার্মেসি — বিস্তারিত', 'ঠিকানা, খোলার সময়, ছাড় ও ডেলিভারি; রেটিং ৪.৫ (১২)।', { full: true });
  });

  await J.sc('S-04', '‘এখন খোলা’ আর ‘২৪ ঘণ্টা’ দিয়ে ছাঁকা', async () => {
    await go(page, '/services/pharmacy');
    await inStore(page);
    await chip(page, 'এখন খোলা');
    await J.shot('শুধু এখন খোলা', '‘ডেমো সেবা ড্রাগ হাউস’ নেই।', { full: true });
    await clearChips(page);
    // ২.২৬.২: ফার্মেসিতেও ‘শুধু ২৪ ঘণ্টা খোলা’ (সার্ভারে open24)। ডেমো লাইফ ফার্মেসিও ২৪ ঘণ্টা (রাতে ডেমো চালাতে, ২.২৬.১)
    await chip(page, '২৪ ঘণ্টা');
    const n24 = await page.locator('article').count();
    const others = await page.locator('article').filter({ hasText: /নিউ মডেল|সেবা ড্রাগ|কালিয়াকৈর/ }).count();
    await J.shot('শুধু ২৪ ঘণ্টা খোলা', '‘ডেমো ২৪ ঘণ্টা ফার্মেসি’ আর ‘ডেমো লাইফ ফার্মেসি’ — দুটোতেই ‘২৪ ঘণ্টা’ ট্যাগ; নিউ মডেল, সেবা, কালিয়াকৈর নেই।', { full: true, ...(n24 === 2 && !others ? {} : { status: 'bug', noteBn: `‘শুধু ২৪ ঘণ্টা খোলা’-তে ${n24}টা দোকান (২টা হওয়ার কথা)।` }) });
    await clearChips(page);
  });

  await J.sc('S-05 S-60', 'প্যাথলজির তালিকা আর দাম (S-60)', async () => {
    await go(page, '/services/lab');
    await area(page);
    await J.shot('অতিথি: প্যাথলজির তালিকা', 'সিটি (বাসা থেকে নমুনা নেয়, ১৭টা পরীক্ষার দাম · ৳১০০ থেকে · ১০% ছাড়), পপুলার, শ্রীপুর, রয়েল; নিউ লাইফ নেই।', { full: true });
    await card(page, 'ডেমো সিটি ডায়াগনস্টিক সেন্টার').click();
    await settle(page, 800);
    await J.shot('সিটি ডায়াগনস্টিক — পরীক্ষা ও দাম', 'সময় সকাল ৮টা–রাত ১০টা; ‘পরীক্ষা ও দাম’: CBC ৳৪০০, HbA1c ৳১,০০০, Lipid ৳১,২০০, TSH ৳৯০০ …; USG নেই; কারো ঠিকানা/ফোন নেই।', { full: true });
    await go(page, '/services/lab');
    await card(page, 'ডেমো রয়েল ল্যাব').click();
    await settle(page);
    await J.shot('রয়েল ল্যাব — দাম জানা নেই', '‘দাম জানা নেই — ফোনে জেনে নিন’।', { full: true });
  });

  await J.sc('S-06', 'বাসায় নার্স / ডাক্তার খোঁজা', async () => {
    await go(page, '/appointment');
    await tap(page, 'বাসায় সেবা');
    await settle(page);
    await tap(page, 'বাসায় নার্স', { role: 'tab' }).catch(() => {});
    await area(page);
    await J.shot('বাসায় নার্স', 'নাসরিন (ইনজেকশন · স্যালাইন …, ফি ৩০০–৫০০), শাহীন, মৌসুমী; ‘ইনজেকশন বা স্যালাইনে ডাক্তারের প্রেসক্রিপশন লাগবে।’; তানিয়া নেই।', { full: true });
    await filter(page, 'শুধু মহিলা');
    await J.shot('শুধু মহিলা', 'শাহীন নেই।', { full: true });
    await clearFilters(page);
    await tap(page, 'বাসায় ডাক্তার', { role: 'tab' }).catch(() => {});
    await J.shot('বাসায় ডাক্তার', '‘ডা. ডেমো রফিক’ (বাসায় রোগী দেখা, ফি ১০০০ টাকা)।', { full: true });
  });

  await J.sc('S-07', 'ওষুধ খুঁজতে লগইন লাগে', async () => {
    await go(page, '/services/pharmacy/medicine');
    await page.getByLabel('ওষুধের নাম').fill('Monte');
    await settle(page, 800);
    await J.shot('অতিথি: ওষুধ খোঁজা — Monte', 'সরকারি তালিকার Montene-র রূপ আসে; জিজ্ঞেস করার জায়গায় ‘দোকানে জিজ্ঞেস করতে লগইন করুন’।', { full: true });
  });

  // ---------------- রোগী ----------------
  await J.sc('S-08', 'আমার ওষুধ খোঁজ: কোন দোকানে আছে', async () => {
    page = await J.as('patient');
    await go(page, '/services/pharmacy/medicine');
    // কতটা লাগবে (MEDFIND.md): Montene ৩ পাতা — ২৪ ঘণ্টায় ৫ পাতা (পুরোটা, আগে), নিউ মডেলে ১ পাতা
    await page.getByText('৩ পাতা লাগবে').first().waitFor();
    await page.getByText('আছে — ৫ পাতা · পুরোটা আছে').first().waitFor();
    await page.getByText('আছে — ১ পাতা (আপনার দরকার ৩)').first().waitFor();
    await J.shot('আমার খোঁজ', 'Montene · ৩ পাতা লাগবে · খোলা · ৩টা দোকানে — ডেমো ২৪ ঘণ্টা ফার্মেসি ‘আছে — ৫ পাতা · পুরোটা আছে’ আগে, ডেমো নিউ মডেল ‘আছে — ১ পাতা (আপনার দরকার ৩)’ (ফোন, রিপোর্ট); কোথাও ‘নেই’ লেখা নেই; Rosuva · বন্ধ।', { full: true });
  });

  await J.sc('S-09', 'নতুন ওষুধ খোঁজা', async () => {
    await page.getByLabel('ওষুধের নাম').fill('Sergel');
    await settle(page, 800);
    await page.getByText(/^Sergel/).first().click().catch(() => {});
    await settle(page);
    // কতটা লাগবে: ক্যাপসুল — শুরুতে ১ পাতা; + দুবার = ৩ পাতা
    await page.getByTestId('qty-plus').click();
    await page.getByTestId('qty-plus').click();
    if ((await page.getByTestId('qty-input').inputValue()) !== '৩') throw new Error('কতটা লাগবে ৩ হয়নি');
    await J.shot('Sergel বাছা', 'Sergel (Esomeprazole 20 mg), কতটা লাগবে: ৩ · ‘পাতা’ বাছা (পাশে ‘পিস’), কোথায়: গাজীপুর।');
    await page.getByRole('button', { name: 'কাছের দোকানগুলোকে জিজ্ঞেস করুন' }).click();
    await settle(page, 600);
    await J.shot('দোকানগুলোকে জিজ্ঞেস করা হলো', '‘কাছের Nটা দোকানকে জিজ্ঞেস করা হয়েছে…’।', { full: true });
  });

  await J.sc('S-10', 'প্রেসক্রিপশনের অনলাইন অর্ডারের অবস্থা', async () => {
    await openRx(page, 'Napa, Seclo, Fexo');
    await J.shot('জ্বরের প্রেসক্রিপশন — অর্ডার অপেক্ষায়', 'ডেমো লাইফ ফার্মেসি — ‘পাঠানো হয়েছে — অপেক্ষায়’, ডেলিভারি ৩০ টাকা, ‘ফিরিয়ে নিন’।', { full: true });
    await openRx(page, 'Montene, Alatrol');
    await J.shot('অ্যাজমার প্রেসক্রিপশন — পথে আছে', 'ডেমো লাইফ — ‘পথে আছে’, ৪৮০ টাকা · আজ সন্ধ্যা ৭টার মধ্যে; ডেমো ২৪ ঘণ্টা — ‘দিতে পারবে না’, ‘ছিল না: Montene’।', { full: true });
    await openRx(page, 'Ceevit');
    await J.shot('ভিটামিন সি — দেওয়া হয়েছে', '‘দেওয়া হয়েছে’, Ceevit ‘কেনা ৩০/৩০’; নতুন অর্ডারের বাটন নেই।', { full: true });
  });

  await J.sc('S-11', 'নতুন অনলাইন অর্ডার দেওয়া (ক্যাশ অন ডেলিভারি)', async () => {
    await openRx(page, 'Comet, Rosuva, Ecosprin');
    await page.getByRole('button', { name: 'ফার্মেসিতে পাঠান' }).last().click();
    await tap(page, 'অনলাইনে অর্ডার — বাসায় ডেলিভারি');
    await settle(page);
    const d = page.getByRole('dialog').last();
    await J.shot('অনলাইনে অর্ডার — দোকান', 'ডেমো লাইফ: ‘৩টার ৩টা ওষুধ আছে’, ডেলিভারি ৩০ টাকা; ডেমো ২৪ ঘণ্টা: ‘নেই: Rosuva’।');
    await d.locator('article, .card').filter({ hasText: 'ডেমো লাইফ ফার্মেসি' }).getByRole('button', { name: 'অর্ডার করুন' }).first().click();
    const o = page.getByRole('dialog').last();
    await o.getByLabel('কোথায় পৌঁছাবে (ঠিকানা)').fill('ডেমো বাসা ৭, শিববাড়ি');
    await o.getByRole('button', { name: 'অর্ডার দিন' }).click();
    await see(page, 'অর্ডার গেছে');
    await J.shot('অর্ডার গেছে', '‘ডেমো লাইফ ফার্মেসি-এ অর্ডার গেছে। ওষুধ পৌঁছালে দাম দেবেন।’', { full: true });
  });

  await J.sc('S-30', 'রোগীর রেটিং: ফার্মেসি (দোকানের সেবা)', async () => {
    await go(page, '/services/pharmacy');
    await inStore(page);
    await card(page, 'ডেমো লাইফ ফার্মেসি').click();
    await settle(page);
    await J.shot('ডেমো লাইফ — সেবার রেটিং', '★ ৪.৫ (১২); ‘ব্যবহার ★৪.৭ · ঠিক ওষুধ ও দাম ★৪.৪ · ডেলিভারি ★৪.১’; ‘রেটিং দিন’ (অর্ডার পেয়েছেন বলে)।', { full: true });
    await tap(page, 'রেটিং দিন', { role: 'button' });
    const d = page.getByRole('dialog').last();
    await J.shot('রেটিংয়ের জানালা', '‘দোকানের সেবা নিয়ে — ওষুধ কেমন কাজ করল তা নয়’; ‘ব্যবহার’, ‘ঠিক ওষুধ ও ন্যায্য দাম’, ‘ডেলিভারির দ্রুততা’।');
    const groups = d.locator('[role=radiogroup], .stars, .rate-row');
    const n = await groups.count();
    for (let i = 0; i < n; i++) await groups.nth(i).locator('button, [role=radio]').nth(4).click().catch(() => {});
    if (!n) { const stars = d.getByRole('button', { name: /৫|5/ }); for (let i = 0; i < (await stars.count()); i++) await stars.nth(i).click().catch(() => {}); }
    const c = d.getByLabel('মন্তব্য (ঐচ্ছিক)');
    if (await c.count()) await c.fill('রাতেও ডেলিভারি দিয়েছে');
    await d.getByRole('button', { name: 'জমা দিন' }).click();
    await settle(page, 600);
    await J.shot('রেটিং দেওয়া হলো', '‘রেটিং দেওয়ার জন্য ধন্যবাদ!’; ★ ৪.৫ (১৩)।', { full: true });
  });

  await J.sc('S-24', 'তালিকার বিরুদ্ধে রিপোর্ট', async () => {
    await go(page, '/services/pharmacy');
    await inStore(page);
    const c = card(page, 'ডেমো কালিয়াকৈর মেডিসিন কর্নার');
    await c.getByRole('button', { name: 'আরও' }).click();
    await tap(page, 'রিপোর্ট');
    await settle(page, 400);
    await J.shot('ফোন না করা দোকানে রিপোর্ট', '‘অ্যাপ থেকে ফোন করার পর রিপোর্ট করতে পারবেন’।');
    await page.keyboard.press('Escape');
    const c2 = card(page, 'ডেমো ২৪ ঘণ্টা ফার্মেসি');
    await c2.getByRole('button', { name: 'আরও' }).click();
    await tap(page, 'রিপোর্ট');
    const d = page.getByRole('dialog').last();
    const t = d.getByLabel('কী হয়েছে');
    if (await t.count()) {
      await t.fill('ডেমো রিপোর্ট — ফোন ধরে না');
      await d.getByRole('button', { name: 'রিপোর্ট' }).last().click();
    }
    await settle(page, 600);
    await J.shot('খোঁজায় ‘আছে’ বলা দোকানে রিপোর্ট', '‘রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।’');
  });

  // ---------------- ফার্মেসি ----------------
  await J.sc('S-12', 'আমার ফার্মেসি: অবস্থা, QR কোড, ফোনের হিসাব', async () => {
    page = await J.as('pharmacy');
    await pharmacyWork(page, 'দোকান');
    await J.shot('আমার ফার্মেসি', '‘ডেমো লাইফ ফার্মেসি’ — ‘তালিকায় আছে’, ফোন ০১২০০০০০১৫০; অ্যাপ থেকে ফোন ৭/৩০/৯০ দিনে; ‘ওষুধ খোঁজার অনুরোধ’।', { full: true });
    await shop(page);
    await J.shot('প্রেসক্রিপশন ও অর্ডার — ওষুধ দিন', 'দোকানের QR আর কোড DEMQKP; ‘আপনার কাছে পাঠানো’, ‘চেয়েও পাননি এমন ওষুধ’।', { full: true });
  });

  await J.sc('S-13', 'নতুন অনলাইন অর্ডার নেওয়া ও পাঠানো', async () => {
    await shop(page);
    await J.shot('অনলাইন অর্ডার — চালু', 'রহিমের জ্বরের অর্ডার (বাসা ১২, রোড ৩, শিববাড়ি; ০১২০০০০০১৫৯; ‘গেটের দারোয়ানের কাছে দিলেও চলবে’) আর S-11-এর নতুন অর্ডার।', { full: true });
    let row = await orderRow(page, 'বাসা ১২', { button: 'অর্ডার নিলাম' });
    await row.getByRole('button', { name: 'অর্ডার নিলাম' }).click();
    const d = page.getByRole('dialog');
    await d.getByLabel('মোট দাম (ঐচ্ছিক)').fill('৩৫০ টাকা');
    await d.getByLabel('কখন পৌঁছাবে (ঐচ্ছিক)').fill('১ ঘণ্টার মধ্যে');
    await d.getByRole('button', { name: 'নিলাম' }).click();
    await see(page, 'রোগীকে জানানো হয়েছে');
    row = await orderRow(page, '৩৫০ টাকা', { button: 'পাঠিয়ে দিয়েছি' });
    await row.getByRole('button', { name: 'পাঠিয়ে দিয়েছি' }).click();
    await settle(page, 600);
    await J.shot('অর্ডার নিলাম আর পাঠিয়ে দিলাম', 'অবস্থা ‘পথে আছে’, ৩৫০ টাকা · ১ ঘণ্টার মধ্যে।', { full: true });
  });

  await J.sc('S-14 S-48', 'পৌঁছে দেওয়া অর্ডার সম্পন্ন করা (কী দিলেন লেখা) + S-48 বিক্রির হিসাবে তোলা', async () => {
    // অ্যাজমার অর্ডার: ৪৮০ টাকা · আজ সন্ধ্যা ৭টার মধ্যে (পথে)
    await (await orderRow(page, '৪৮০', { button: 'খুলে ওষুধ লিখুন' })).getByRole('button', { name: 'খুলে ওষুধ লিখুন' }).click();
    await settle(page);
    await page.getByLabel('Montene: কয়টা দিলেন').fill('40');
    await page.getByRole('button', { name: 'যা দিলাম লিখে রাখুন' }).click();
    await settle(page, 400);
    await J.shot('বেশি লিখলে আটকায়', '‘Montene: এই প্রেসক্রিপশনে আর ৩০টা বাকি’।');
    await page.getByLabel('Montene: কয়টা দিলেন').fill('30');
    await page.getByLabel('Alatrol: কয়টা দিলেন').fill('10');
    await page.getByRole('button', { name: 'যা দিলাম লিখে রাখুন' }).click();
    await see(page, 'লেখা হয়েছে');
    await J.shot('লেখা হয়েছে — বিক্রির হিসাবে তুলবেন?', '‘লেখা হয়েছে’; নিচে ‘বিক্রির হিসাবে তুলবেন?’ — ‘অনলাইন’, Montene ×৩০, Alatrol ×১০, ‘মোট ৳৪৬৭’।', { full: true });
    const up = page.getByRole('button', { name: 'হিসাবে তুলুন' });
    if (await up.count()) {
      await up.click();
      await see(page, 'বিক্রির হিসাবে উঠেছে');
      await J.shot('বিক্রির হিসাবে উঠেছে', '‘বিক্রির হিসাবে উঠেছে’।');
    }
  });

  await J.sc('S-16', 'স্টকের তালিকা আর ‘চেয়েও পাননি’', async () => {
    await pharmacyWork(page, 'দোকান');
    const brought = page.locator('div, li').filter({ hasText: 'Mixtard 30' }).getByRole('button', { name: 'এনেছি' }).first();
    await J.shot('চেয়েও পাননি এমন ওষুধ', 'Mixtard 30 (৪ বার), Thyrox 50 (২ বার), Nexum 20 (১ বার)।', { full: true });
    await brought.click();
    await settle(page, 500);
    await J.shot('Mixtard 30 — এনেছি', 'Mixtard 30 তালিকা থেকে সরে স্টকে যোগ হয়।', { full: true });
  });

  await J.sc('S-17', 'ওষুধ খোঁজার অনুরোধে উত্তর', async () => {
    // ২.৩০.০: আলাদা ট্যাব "ওষুধ খোঁজা"
    await go(page, '/work/pharmacy/finds');
    // কতটা লাগবে দেখায়; আছে হলে কতটা আছে লিখে পাঠান — "নেই" বোতাম নেই; না থাকলে চুপচাপ "বাদ দিন" (MEDFIND.md)
    await page.getByText('১৪ পিস লাগবে').first().waitFor();
    if (await page.getByRole('button', { name: 'নেই', exact: true }).count()) throw new Error('‘নেই’ বোতাম থাকার কথা নয়');
    await J.shot('ওষুধ খোঁজার অনুরোধ — কতটা লাগবে', 'Sergel ‘১৪ পিস লাগবে’, Montene ‘৩ পাতা লাগবে’; প্রতিটায় ‘কতটা আছে’ ঘর, একক, ‘পাঠান’ আর হালকা ‘বাদ দিন’; ক্রেতার নাম-ফোন নেই; ‘নেই’ বোতাম নেই।', { full: true });
    const sg = page.locator('tr, article, div').filter({ hasText: 'Sergel' }).filter({ has: page.getByTestId('find-have-qty') }).last();
    await sg.getByTestId('find-have-qty').fill('20');
    await sg.getByTestId('find-have-send').click();
    await page.getByText(/আপনার উত্তর: আছে — ২০ (পিস|পাতা)/).first().waitFor();
    const mt = page.locator('tr, article, div').filter({ hasText: 'Montene' }).filter({ has: page.getByTestId('find-dismiss') }).last();
    if (await mt.count()) await mt.getByTestId('find-dismiss').click();
    await settle(page, 500);
    await J.shot('ওষুধ খোঁজার অনুরোধ — কতটা আছে', 'Sergel ‘আপনার উত্তর: আছে — ২০ …’ (পাশে ‘বদলান’); Montene ‘বাদ দিন’-এ তালিকা থেকে সরে গেছে।', { full: true });
  });

  await J.sc('S-18', 'ফার্মেসির তথ্য বদলানো আর কয়েক দিন বন্ধ রাখা', async () => {
    await pharmacyWork(page, 'দোকান');
    await tap(page, 'তথ্য বদলান');
    await settle(page);
    await J.shot('ফার্মেসির তথ্য বদলান', 'সময়, ছাড়, ডেলিভারি, লাইসেন্স।', { full: true });
    const disc = page.getByLabel(/ছাড়/).first();
    if (await disc.count()) await disc.fill('12');
    const terms = page.getByRole('checkbox').last();
    if (await terms.count()) await terms.check({ force: true }).catch(() => {});
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await see(page, 'সেভ হয়েছে');
    await J.shot('সেভ হয়েছে — অনুমোদন লাগে না', '‘সেভ হয়েছে’ — তালিকায় থাকে।');
    await pharmacyWork(page, 'দোকান');
    await tap(page, 'কয়েক দিন বন্ধ থাকবে');
    const d = page.getByRole('dialog').last();
    await d.getByText(/^৩ দিন/).first().click();
    const ok = d.getByRole('button', { name: /সেভ|বন্ধ রাখুন|ঠিক আছে/ });
    if (await ok.count()) await ok.last().click();
    await settle(page, 600);
    await J.shot('৩ দিন বন্ধ', '‘বন্ধ থাকবে <তারিখ> পর্যন্ত’ আর ‘আবার খুলুন’।', { full: true });
    await tap(page, 'আবার খুলুন');
    await settle(page, 500);
    await J.shot('আবার খোলা', 'আগের মতো খোলা।');
  });

  await J.sc('S-40', 'পণ্য ও স্টকের তালিকা (দাম, পরিমাণ, মেয়াদ)', async () => {
    await go(page, '/work/pharmacy/shop/stock');
    await J.shot('পণ্য ও স্টক', '‘২৬টা পণ্য’; পরিমাণ, কেনা দাম, বিক্রি দাম, ছাড় (Seclo: ৳৭, −১০% = ৳৬.৩), মেয়াদ, ‘স্টকে আছে’।', { full: true });
    await tap(page, 'কম স্টক');
    await J.shot('কম স্টক (৫)', 'Nexum 20, Thyrox 50, Cef-3 (৪), Rosuva (৬), Montene (৮)।', { full: true });
    await tap(page, 'মেয়াদ শেষের পথে');
    await J.shot('মেয়াদ শেষের পথে (২)', 'Ceevit, Azithrocin (লাল)।', { full: true });
    await tap(page, 'মেয়াদ শেষের পথে');
  });

  await J.sc('S-41', 'নতুন পণ্য: শুধু নাম, বা দাম সহ', async () => {
    const add = page.getByLabel('পণ্য যোগ করুন').or(page.getByPlaceholder('ওষুধের নাম লিখুন')).first();
    await add.fill('Neurobion');
    await settle(page, 700);
    // Neurobion সরকারি তালিকায় নেই — ‘"Neurobion" যোগ করুন’ চাপলে ‘নতুন পণ্য’ ঘর (নাম বসানো), শুধু ‘সেভ’
    await page.getByRole('button', { name: /Neurobion.*যোগ করুন/ }).first().click();
    const nd = page.getByRole('dialog').last();
    await nd.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 500);
    await J.shot('Neurobion — শুধু নাম', 'Neurobion তালিকায় (পরিমাণ/দাম ‘—’), ‘স্টকে আছে’ চালু। (সরকারি তালিকায় নেই বলে ‘নতুন পণ্য’ ঘর দিয়ে)', { full: true });
    await add.fill('Omidon');
    await settle(page, 700);
    await page.getByRole('button', { name: 'দাম সহ' }).first().click();
    const d = page.getByRole('dialog').last();
    await d.getByLabel('পরিমাণ').fill('50');
    await d.getByLabel('একক').selectOption({ label: 'পিস' }).catch(() => {});
    await d.getByLabel('কেনা দাম (প্রতি একক)').fill('2.2');
    await d.getByLabel('বিক্রি দাম / MRP (প্রতি একক)').fill('3');
    const dp = d.locator('input[inputmode="decimal"], input[type="number"]').filter({ hasNot: d.locator('[aria-label]') });
    const disc = d.locator('input').nth(4);
    await disc.fill('5').catch(() => {});
    await d.getByLabel('কত থাকলে আবার আনবেন').fill('20').catch(() => {});
    void dp;
    await J.shot('Omidon — দাম সহ', '‘নিচের সব ঐচ্ছিক।’; ছাড় ৫% দিলে ‘বিক্রি হবে ৳২.৮৫’।');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 500);
    await J.shot('Omidon যোগ হলো', 'Omidon: ৫০ পিস, কেনা ৳২.২, বিক্রি ৳৩, −৫% = ৳২.৮৫।', { full: true });
  });

  await J.sc('S-45', 'কাউন্টারে বিক্রি — পণ্যের ছাড় আর বিলে ছাড়', async () => {
    await tap(page, 'বিক্রি', { role: 'tab' });
    await posNoPrint(page);
    const pick = posPicker(page);
    await pick.fill('Comet');
    await settle(page, 500);
    await pick.press('Enter');
    await page.locator('input').and(page.getByLabel(/কয়টা/)).first().fill('20');
    await pick.fill('Seclo');
    await settle(page, 500);
    await pick.press('Enter');
    await page.locator('input').and(page.getByLabel(/কয়টা/)).last().fill('10');
    const bill = page.locator('input[aria-label="বিলে ছাড়"]').first();
    if (await bill.count()) await bill.fill('5');
    await settle(page, 300);
    await J.shot('কাউন্টারে বিক্রি — Comet ২০, Seclo ১০, বিলে ৫%', 'মোট ৳১৭০, ছাড় ৳২৪.৬৫, দিতে হবে ৳১৪৫.৩৫।', { full: true });
    await posSave(page, J);
    await J.shot('বিক্রি লেখা হয়েছে', '‘বিক্রি লেখা হয়েছে · ৳১৪৫.৩৫’; ডানে ‘কাউন্টার’ ট্যাগ।', { full: true });
  });

  await J.sc('S-50 S-51', 'হিসাব: আজ, ৭ দিন, ৩০ দিন', async () => {
    await pharmacyWork(page, 'হিসাব');
    await J.shot('হিসাব — ৭ দিন', 'বিক্রি, মোট লাভ, মার্জিন, নিট লাভ; দিনের বিক্রি ও লাভের চার্ট।', { full: true });
    await tap(page, '৩০ দিন');
    await J.shot('হিসাব — ৩০ দিন', 'বেশি বিক্রি (Comet, Rosuva, Calbo), কেনা দামের নিচে বিক্রি (Flagyl), স্টকের দাম, কম স্টক, মেয়াদ, ৩০ দিনে বিক্রি নেই।', { full: true });
  });

  await J.sc('S-52', 'শুধু দোকানের মালিক হিসাব দেখেন', async () => {
    page = await J.as('patient', 'desktop');
    await go(page, '/work/pharmacy/orders/books');
    await J.shot('রোগী হিসাবের পাতা খুললে', '‘আগে আপনার ফার্মেসি যোগ করুন’ — অন্যের দোকানের হিসাব দেখা যায় না।');
    await see(page, 'আগে আপনার ফার্মেসি যোগ করুন');
  });

  // ---------------- প্যাথলজি ----------------
  await J.sc('S-19', 'আমার প্যাথলজি', async () => {
    page = await J.as('lab');
    await labWorkTab(page, 'ল্যাবের তথ্য');
    await J.shot('আমার প্যাথলজি', '‘তালিকায় আছে’, ফোন ০১২০০০০০১৬০; পরীক্ষা ও রিপোর্টের অংশ।', { full: true });
  });

  await J.sc('S-61', 'প্যাথলজি: পরীক্ষা ও দামের তালিকা', async () => {
    await labWork(page, 'পরীক্ষা ও দাম');
    await J.shot('পরীক্ষা ও দাম', '‘১৭টা পরীক্ষা’; USG-তে টিক নেই (‘এখন হয় না’); Serum Vitamin B12 ‘নিজে লেখা’; ছাড় ১০, বাসা থেকে নমুনা ১৫০।', { full: true });
  });

  await J.sc('S-62', 'প্যাথলজি: দাম বদলানো, পরীক্ষা যোগ', async () => {
    const cbc = page.getByLabel(/CBC.*দাম|দাম.*CBC/).first();
    if (await cbc.count()) await cbc.fill('450');
    else await page.locator('tr').filter({ hasText: 'CBC' }).locator('input').first().fill('450');
    const s = page.getByPlaceholder(/খুঁজুন|যোগ/).first();
    await s.fill('ferritin');
    await settle(page, 600);
    await J.shot('পরীক্ষা যোগ — ferritin', '‘Serum Ferritin’-এর পাশে ‘যোগ’।');
    await page.getByRole('button', { name: /^যোগ/ }).first().click().catch(() => {});
    await settle(page, 300);
    await page.getByRole('button', { name: 'সেভ করুন' }).last().click();
    await settle(page, 600);
    await J.shot('দাম খালি থাকলে সেভ হয় না / সেভ হলে নিশ্চিত', 'দাম খালি: লাল ঘর আর ‘… : দাম লিখুন’; দাম দিলে ‘সেভ হয়েছে — রোগীরা এখন এই দাম দেখবেন’।', { full: true });
  });

  await J.sc('S-67', 'প্যাথলজি: বাসা থেকে নমুনা — সময় নিশ্চিত (ফাতেমা)', async () => {
    await labWork(page, 'পরীক্ষার অনুরোধ');
    await page.locator('tr, article').filter({ hasText: 'ডেমো রোগী ফাতেমা বেগম' }).first().getByText('ডেমো রোগী ফাতেমা বেগম').click();
    await settle(page);
    await J.shot('ফাতেমার অনুরোধ — বাসা থেকে নমুনা', 'ঠিকানা ‘ডেমো বাসা ৫, স্কুল রোড, ছায়াবীথি’, ফোন, ‘চেয়েছেন: কাল · সকাল ৭–৯’, ‘খালি পেটে থাকব’।', { full: true });
    await page.getByRole('button', { name: 'সময় নিশ্চিত করুন' }).first().click();
    const d = page.getByRole('dialog').last();
    const note = d.getByLabel(/রোগীর জন্য নোট/);
    if (await note.count()) await note.fill('রাত ১০টার পর কিছু খাবেন না');
    await J.shot('সময় নিশ্চিত করুন', 'দিন ‘কাল’, সময় ‘সকাল ৭–৯’, ঠিক সময়, নোট।');
    await d.getByRole('button', { name: /রোগীকে জানান/ }).click();
    await see(page, 'রোগীকে সময় জানানো হয়েছে');
    await J.shot('সময় নিশ্চিত', '‘রোগীকে সময় জানানো হয়েছে’; অবস্থা ‘সময় নিশ্চিত’।', { full: true });
  });

  // ---------------- অ্যাম্বুলেন্স চালক ----------------
  await J.sc('S-20 S-87', 'আমার অ্যাম্বুলেন্স: খালি/ট্রিপে আর হিসাব', async () => {
    page = await J.as('ambulance');
    await go(page, '/work/ambulance');
    await J.shot('আমার অ্যাম্বুলেন্স', '৪টা গাড়ি; মিনিতে ‘খালি আছে — আর … মিনিট’, ‘আবার চালু রাখুন’; আইসিইউ ‘অনুমোদনের অপেক্ষায়’।', { full: true });
    const again = page.getByRole('button', { name: 'আবার চালু রাখুন' });
    if (await again.count()) {
      await again.first().click();
      await settle(page, 600);
      await J.shot('আবার চালু রাখুন (S-87)', 'সবুজ ঘর চলে যায়; ‘খালি জানিয়েছেন এইমাত্র’।', { full: true });
    }
  });

  await J.sc('S-21', 'অ্যাম্বুলেন্সের তথ্য বদলানো (ভাড়া)', async () => {
    const c = page.locator('article, .card').filter({ hasText: 'ডেমো জীবন অ্যাম্বুলেন্স (এসি)' }).filter({ has: page.getByRole('link', { name: /বদলান/ }).or(page.getByRole('button', { name: /বদলান/ })) }).first(); // "রোগীরা আপনাকে যেভাবে দেখেন"-এর কার্ড নয়
    await c.getByRole('link', { name: /বদলান/ }).or(c.getByRole('button', { name: /বদলান/ })).first().click();
    await settle(page);
    const fare = page.getByLabel(/ভাড়া নিয়ে নোট/).first();
    await fare.fill('শহরের মধ্যে ৯০০ টাকা, ঢাকা ৫৫০০ টাকা');
    const terms = page.getByRole('checkbox').last();
    if (await terms.count()) await terms.check({ force: true }).catch(() => {});
    await J.shot('ভাড়া বদলানো', 'ভাড়া ‘শহরের মধ্যে ৯০০ টাকা…’।', { full: true });
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await see(page, 'সেভ হয়েছে');
    await J.shot('ভাড়া সেভ হলো', '‘সেভ হয়েছে’ — তালিকায় নতুন ভাড়া।');
  });

  // ---------------- নার্স ----------------
  await J.sc('S-22', 'নার্সের নিজের বাসায় সেবা', async () => {
    page = await J.as('homecare');
    await go(page, '/work');
    await J.shot('নার্স: আজকের কাজ', '‘বাসায় সেবা’ অংশ; নিচে ‘আপনার কাজের পাতা’-য় ‘আমার বাসায় সেবা’।', { full: true });
    await page.getByRole('link', { name: /বাসায় সেবা/ }).first().click();
    await settle(page);
    await J.shot('আমার বাসায় সেবা', '‘আপনি তালিকায় আছেন — রোগীরা সরাসরি ফোন করবেন।’, ফোনের হিসাব, রেটিং ৪.৫ (৬); সেটিং: ১০ কিমি, সদর আর কালিয়াকৈর, ০১২০০০০০১৮০।', { full: true });
  });

  // ---------------- মালিক (অ্যাডমিন প্যানেল) ----------------
  await J.sc('S-26', 'প্যানেলে অনুমোদিত তালিকা দেখা', async () => {
    page = await owner(J);
    await adminTab(page, 'ফার্মেসি', 'অনুমোদিত');
    await J.shot('অ্যাডমিন: ফার্মেসি — অনুমোদিত', 'ডেমো লাইফ (লাইসেন্স DEMO-DL-1501), ২৪ ঘণ্টা, নিউ মডেল, সেবা ড্রাগ হাউস, কালিয়াকৈর মেডিসিন কর্নার।', { full: true , status: 'fixed', noteBn: 'আগে অ্যাডমিনের অনুমোদিত তালিকার ‘বদল / নোট’ ঘরে ফার্মেসির রেটিংয়ের হিসাব, অনলাইন বিক্রির সেটিং আর ডেমোর চিহ্নকে ‘বদল’ ধরে দেখাত — ‘aspects: আগে — → এখন [object Object]’। আর মালিকের কোনো বদল বাতিল করলে আগের তথ্য ফেরানোর সময় দোকানের দিকভিত্তিক রেটিং আর অনলাইন সেটিং মুছে বা পুরোনো হয়ে যেত। এখন এগুলো বদলের হিসাবে আসে না, হারায়ও না। (সার্ভারের পরীক্ষা যোগ হয়েছে)' });
    await adminTab(page, 'প্যাথলজি', 'অনুমোদিত');
    await J.shot('অ্যাডমিন: প্যাথলজি — অনুমোদিত', 'সিটি, পপুলার, শ্রীপুর (‘নিজে লেখা’)।', { full: true });
    await adminTab(page, 'অ্যাম্বুলেন্স', 'অনুমোদিত');
    await J.shot('অ্যাডমিন: অ্যাম্বুলেন্স — অনুমোদিত', '৬টা ডেমো গাড়ি, ধরন আর ভাড়া; ‘বদল / নোট’-এ শুধু যাচাইয়ের তথ্য (ডেমোর চিহ্ন বদল হিসেবে নয়)।', { full: true, status: 'fixed', noteBn: 'আগে প্রতিটা ডেমো গাড়ির পাশে ‘demo: আগে — → এখন হ্যাঁ’ লেখা আসত — ডেমোর চিহ্নকে মালিকের বদল ধরছিল। এখন আসে না।' });
  });

  await J.sc('S-27', 'ফার্মেসি অনুমোদন (কাগজ দেখে)', async () => {
    await adminTab(page, 'ফার্মেসি', 'অপেক্ষায়');
    await approveFirst(page, 'ডেমো আরোগ্য ফার্মেসি');
    await J.shot('আরোগ্য ফার্মেসি — যাচাই', '‘DEMO DOCUMENT’ ছবি (ড্রাগ লাইসেন্স, দোকানের ছবি); ‘আরও Nটা বিষয় বাকি’।', { full: true });
    await tickAll(page);
    await page.getByRole('button', { name: 'অনুমোদন দিন' }).click();
    await settle(page, 600);
    await J.shot('আরোগ্য অনুমোদিত', '‘যাচাই করে অনুমোদন দেওয়া হয়েছে’।');
  });

  await J.sc('S-28', 'প্যাথলজি আর অ্যাম্বুলেন্স অনুমোদন', async () => {
    await adminTab(page, 'প্যাথলজি', 'অপেক্ষায়');
    await approveFirst(page, 'ডেমো নিউ লাইফ ল্যাব');
    await tickAll(page);
    await J.shot('নিউ লাইফ ল্যাব — সব টিক', 'সব টিক, ‘সব মিলেছে’।', { full: true });
    await page.getByRole('button', { name: 'অনুমোদন দিন' }).click();
    await settle(page, 600);
    await adminTab(page, 'অ্যাম্বুলেন্স', 'অপেক্ষায়');
    await approveFirst(page, 'ডেমো জীবন আইসিইউ অ্যাম্বুলেন্স');
    await J.shot('আইসিইউ অ্যাম্বুলেন্স — যাচাই', 'রেজিস্ট্রেশন, ফিটনেস, গাড়ির ছবি; টিকের তালিকা।', { full: true });
    await tickAll(page);
    await page.getByRole('button', { name: 'অনুমোদন দিন' }).click();
    await settle(page, 600);
    await J.shot('আইসিইউ অনুমোদিত', '‘যাচাই করে অনুমোদন দেওয়া হয়েছে’।');
  });

  await J.sc('S-29', 'রিপোর্ট মীমাংসা', async () => {
    await adminTab(page, 'রিপোর্ট');
    await J.shot('অ্যাডমিন: রিপোর্ট', 'ফার্মেসি (নিউ মডেল), অ্যাম্বুলেন্স (মমতা), বাসায় সেবা (শাহীন), আর ২৪ ঘণ্টা ফার্মেসি (S-24)।', { full: true });
    const r = page.locator('tr').filter({ hasText: 'ডেমো নিউ মডেল ফার্মেসি' }).first();
    await adminAct(page, r.getByRole('button', { name: 'রেখে দিন' }).first());
    await settle(page, 600);
    await J.shot('নিউ মডেলের রিপোর্ট রেখে দেওয়া', '‘রেখে দেওয়া হয়েছে’ — রিপোর্ট সরে, দোকান তালিকায় থাকে।', { full: true });
  });
}
