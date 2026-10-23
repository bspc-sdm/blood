// বাকি দৃশ্যগুলো (আগের যাত্রার পরে যেগুলো করা যায়): দোকানের হিসাব-খাতা, প্যাথলজির দাম আর বাসা থেকে নমুনা,
// অ্যাম্বুলেন্সের জায়গা আর মনে করানো, প্যাথলজির রিপোর্ট, ব্যাজ সংশোধন/বাতিল, চেম্বারের গড় সময়, আপিল
import { tap, see, has, go, settle, logout, pharmacyWork, labWorkTab, orderRow, rowAct, posPicker, posNoPrint, posSave, rxListAll } from '../lib.mjs';
import { BASE } from '../stack.mjs';
import { adminLogin } from './00-seed.mjs';

export const meta = { no: 8, slug: 'more', titleBn: 'আরও: দোকানের হিসাব, প্যাথলজির দাম, অ্যাম্বুলেন্স, ব্যাজ' };

async function shop(page, tab) {
  await pharmacyWork(page, tab);
}
async function labWork(page, tab) {
  await labWorkTab(page, tab);
}
const itemRow = (page, name) => page.locator('tr').filter({ has: page.getByText(name, { exact: true }) }).first();
async function pickProduct(page, name) {
  const p = posPicker(page);
  await p.fill(name);
  await settle(page, 500);
  await p.press('Enter');
  await settle(page, 300);
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
async function openA222(page) {
  await rxListAll(page);
  await page.locator('article').filter({ hasText: /Secrin/ }).first().click();
  await settle(page);
}

export async function run(J) {
  let page;

  // ---------------- দোকানের হিসাব-খাতা ----------------
  await J.sc('S-42', 'পরিমাণ বদলালে স্টকের তালিকাও বদলায়', async () => {
    page = await J.as('pharmacy');
    await shop(page, 'পণ্য ও স্টক');
    await itemRow(page, 'Rosuva').getByRole('button', { name: 'বদলান' }).first().click();
    const d = page.getByRole('dialog').last();
    await d.getByLabel('পরিমাণ').fill('0');
    await J.shot('Rosuva পরিমাণ ০', 'পরিমাণের ঘরের নিচে ‘বদলালে "গণনা ঠিক করা" হিসেবে থাকবে’।');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 500);
    await J.shot('Rosuva — স্টকে নেই', 'লাল ‘স্টকে নেই’, সুইচ বন্ধ।', { full: true });
    await itemRow(page, 'Rosuva').getByRole('button', { name: 'বদলান' }).first().click();
    const d2 = page.getByRole('dialog').last();
    await d2.getByLabel('পরিমাণ').fill('6');
    await d2.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 500);
  });

  await J.sc('S-43', 'মাল কেনা — গড় কেনা দাম', async () => {
    await tap(page, 'মাল কেনা', { role: 'tab' });
    await page.getByLabel('কার কাছ থেকে (ঐচ্ছিক)').fill('ডেমো বেক্সিমকো ডিপো');
    await pickProduct(page, 'Cef');
    let line = page.locator('.bk-line').last();
    await line.locator('[data-bk-qty]').fill('100');
    await line.locator('input').nth(1).fill('34');
    await pickProduct(page, 'Montene');
    line = page.locator('.bk-line').last();
    await line.locator('[data-bk-qty]').fill('60');
    await settle(page, 300);
    await J.shot('মাল কেনা — Cef-3 ১০০×৩৪, Montene ৬০', 'নিচে ‘মোট ৳৪,১৮০’।', { full: true });
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await see(page, 'সেভ হয়েছে — স্টক বেড়েছে');
    await J.shot('কেনা সেভ হলো', '‘সেভ হয়েছে — স্টক বেড়েছে’; ‘আগের কেনা’-তে ওপরে ডেমো বেক্সিমকো ডিপো ৳৪,১৮০।', { full: true });
    await tap(page, 'স্টক', { role: 'tab', exact: true });
    await J.shot('গড় কেনা দাম', 'Cef-3: ১০৪ পিস, কেনা ৳৩৩.৯২; Montene ৬৮ পিস; দুটোই আর কম স্টক নয়।', { full: true });
  });

  await J.sc('S-44', 'ভুল কেনা মোছা', async () => {
    await tap(page, 'মাল কেনা', { role: 'tab' });
    await page.getByRole('button', { name: 'মুছুন' }).first().click();
    const d = page.getByRole('dialog').last();
    await J.shot('এই কেনা মুছবেন?', '‘পরিমাণগুলো স্টক থেকে বাদ যাবে।’');
    await d.getByRole('button', { name: 'মুছুন' }).click();
    await settle(page, 500);
    await tap(page, 'স্টক', { role: 'tab', exact: true });
    await J.shot('মোছার পর স্টক', 'Cef-3 আবার ৪ পিস, Montene ৮ পিস।', { full: true });
  });

  await J.sc('S-46 S-47', 'কেনা দামের নিচে বিক্রি, আর ভুল বিক্রি মোছা', async () => {
    await tap(page, 'বিক্রি', { role: 'tab' });
    await posNoPrint(page);
    await pickProduct(page, 'Flagyl');
    await page.locator('input').and(page.getByLabel(/কয়টা/)).last().fill('21');
    await settle(page, 300);
    await J.shot('Flagyl — কেনা দামের নিচে', 'লাইনের নিচে লাল ‘কেনা দামের নিচে’।', { full: true });
    await posSave(page);
    await J.shot('বিক্রির তালিকায় ক্ষতি', 'এই বিক্রির নিচে লাল ‘ক্ষতি ৳৮.৪’।', { full: true });
    await tap(page, '৭ দিন');
    await J.shot('৭ দিনের বিক্রি', 'তারিখসহ সব বিক্রি, ‘অনলাইন’ ট্যাগও আছে।', { full: true });
    await tap(page, 'আজ', { exact: true });
    const sale = page.locator('li').filter({ hasText: 'Flagyl ×২১' }).first();
    if (await sale.getByRole('button', { name: 'রসিদ দেখুন' }).count()) {
      // রসিদ চালু (inv_pharmacy): রসিদের বিক্রি মোছা যায় না (INVOICES.md I2) — দোকান › রসিদ-এ রসিদ বাতিল, জিনিস স্টকে ফেরে
      await go(page, '/work/pharmacy/shop/invoices');
      await page.locator('[data-testid=biz-invoice]').filter({ hasText: 'Flagyl' }).first().getByTestId('row-void').click();
      const d = page.getByRole('dialog').last();
      await d.locator('[data-reason=wrong_item]').click();
      await d.getByLabel('বিস্তারিত লিখুন (দিতেই হবে)').fill('ডেমো: ভুল করে বিক্রি লেখা');
      await J.shot('এই রসিদ বাতিল করবেন?', '‘রসিদ কখনো মোছে না… জিনিসগুলো স্টকে ফিরে যাবে।’; কারণ ‘ভুল পণ্য’।');
      await d.getByTestId('void-confirm').click();
      await see(page, 'রসিদ বাতিল হয়েছে');
      await settle(page, 500);
      await J.shot('রসিদ বাতিল', 'তালিকায় Flagyl-এর রসিদ ‘বাতিল’, নিচে ‘বাতিলের কারণ: ভুল পণ্য’।', { full: true });
      return;
    }
    await sale.getByRole('button', { name: 'মুছুন' }).click();
    const d = page.getByRole('dialog').last();
    await J.shot('এই বিক্রি মুছবেন?', '‘পরিমাণগুলো স্টকে ফিরে যাবে।’');
    await d.getByRole('button', { name: 'মুছুন' }).click();
    await settle(page, 500);
  });

  await J.sc('S-49', 'নষ্ট/মেয়াদ শেষ আর গণনা ঠিক করা', async () => {
    await pharmacyWork(page, 'স্টক');
    await itemRow(page, 'Ceevit').getByRole('button', { name: 'নষ্ট/গণনা' }).click();
    let d = page.getByRole('dialog').last();
    await d.getByLabel('কয়টা').fill('10');
    await d.getByLabel('কারণ (ঐচ্ছিক)').fill('মেয়াদ শেষ');
    await J.shot('Ceevit — নষ্ট ১০', '‘এখন: ১৫০ পিস’; নিচে ‘ক্ষতি ৳১২’।');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 500);
    await itemRow(page, 'Maxpro').getByRole('button', { name: 'নষ্ট/গণনা' }).click();
    d = page.getByRole('dialog').last();
    await d.getByText('গণনা ঠিক করা').click();
    await d.getByLabel('গুনে পেলেন').fill('145');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 500);
    await J.shot('Ceevit ১৪০, Maxpro ১৪৫', 'Ceevit ১৪০ পিস; Maxpro ১৫০ → ১৪৫।', { full: true });
    await pharmacyWork(page, 'এনালাইটিক্স');
    await tap(page, 'আজ', { exact: true });
    await J.shot('হিসাব — আজ', '‘নষ্ট / মেয়াদ শেষে ক্ষতি ৳১২’, ‘নিট লাভ’ ১২ টাকা কম।', { full: true });
  });

  // ---------------- প্যাথলজির দাম ----------------
  await J.sc('S-63', 'প্যাথলজি: ছাড় আর বাসার চার্জ', async () => {
    page = await J.as('lab');
    await labWork(page, 'পরীক্ষা ও দাম');
    const disc = page.getByLabel('সব পরীক্ষায় ছাড় (%)');
    await disc.fill('95');
    await page.getByRole('button', { name: 'সেভ করুন' }).last().click();
    await settle(page, 400);
    await J.shot('ছাড় ৯৫ — আটকায়', '‘ছাড় ০–৯০% এর মধ্যে দিন’।', { full: true });
    await disc.fill('10');
  });

  await J.sc('S-71', 'পাঠানোর সময়ের দাম থেকে যায়', async () => {
    const hb = page.locator('tr').filter({ hasText: 'HbA1c' }).locator('input').first();
    const old = await hb.inputValue();
    await hb.fill('1200');
    await page.getByRole('button', { name: 'সেভ করুন' }).last().click();
    await settle(page, 600);
    await J.shot('HbA1c ১২০০ সেভ', '‘সেভ হয়েছে — রোগীরা এখন এই দাম দেখবেন’।', { full: true });
    await labWork(page, 'পরীক্ষার অনুরোধ');
    await (await orderRow(page, 'ডেমো রোগী ফাতেমা বেগম')).getByText('ডেমো রোগী ফাতেমা বেগম').click();
    await settle(page);
    await J.shot('ফাতেমার অনুরোধে আগের দাম', '‘আনুমানিক ৳২,০৭০’ — পাঠানোর সময়ের হিসাব।', { full: true });
    page = await J.as('patient');
    await openA222(page);
    await tap(page, 'পরীক্ষা করাতে পাঠান', { role: 'button' });
    await settle(page, 700);
    await J.shot('রোগীর নতুন বাছাইয়ে নতুন দাম (S-65)', 'সিটি: ‘৩টার ৩টা পরীক্ষা · আনুমানিক ৳২,২৫০’; পপুলার, শ্রীপুর (‘এখানে হয় না: FBS, Lipid profile’), রয়েল (‘দাম জানা নেই’)।');
    const d = page.getByRole('dialog').last();
    // শুধু HbA1c
    for (const t of ['FBS', 'Lipid profile']) await d.getByText(t, { exact: true }).first().click();
    await settle(page, 800);
    await J.shot('শুধু HbA1c — সবচেয়ে কম দাম আগে', 'শ্রীপুর ৳৮৫৫ সবার আগে, তারপর সিটি, পপুলার।');
    for (const t of ['FBS', 'Lipid profile']) await d.getByText(t, { exact: true }).first().click();
    await settle(page, 600);
    // S-74: শ্রীপুরে পাঠানো (সব পরীক্ষা হয় না)
    await d.locator('article, .card').filter({ hasText: 'ডেমো শ্রীপুর ডায়াগনস্টিক' }).getByRole('button', { name: 'পরীক্ষা পাঠান' }).first().click();
    const h = page.getByRole('dialog').last();
    const lab = h.getByText('প্যাথলজিতে গিয়ে দেব');
    if (await lab.count()) { await lab.click(); await h.getByRole('button', { name: 'পাঠান', exact: true }).click(); }
    await settle(page, 600);
    await J.shot('শ্রীপুরে পাঠানো (S-74)', 'শ্রীপুরের সারিতে ‘আনুমানিক ৳৮৫৫ · FBS, Lipid profile দাম জানা নেই’।', { full: true });
    page = await J.as('lab');
    await labWork(page, 'পরীক্ষা ও দাম');
    await page.locator('tr').filter({ hasText: 'HbA1c' }).locator('input').first().fill(old || '1000');
    await page.getByRole('button', { name: 'সেভ করুন' }).last().click();
    await settle(page, 500);
  });

  await J.sc('S-72', 'রোগী: বাসা থেকে নমুনার সময় বদলানো (কেয়ারগিভারের অনুরোধে)', async () => {
    page = await J.as('patient');
    await openA222(page);
    await page.getByRole('button', { name: 'সময় বদলান' }).first().click();
    const d = page.getByRole('dialog').last();
    await J.shot('সময় বদলান — আগের ঠিকানা/ফোন বসানো', 'ওপরে ‘ডেমো সিটি ডায়াগনস্টিক সেন্টার — সময় বদলান’।');
    await d.getByText(/বিকেল|বিকাল/).first().click().catch(() => {});
    await d.getByRole('button', { name: 'বদলান' }).click();
    await see(page, 'প্যাথলজিকে নতুন সময় জানানো হয়েছে');
    await J.shot('নতুন সময় জানানো হলো', '‘প্যাথলজিকে নতুন সময় জানানো হয়েছে’; সারিতে ‘বাসা থেকে নমুনা · … — সময় জানাবে’।', { full: true });
  });

  await J.sc('S-68 S-70', 'প্যাথলজি: বাসায় যেতে পারব না', async () => {
    page = await J.as('lab');
    await labWork(page, 'পরীক্ষার অনুরোধ');
    const row = await orderRow(page, ['ডেমো রোগী রহিম', 'FBS']);
    await row.getByText('ডেমো রোগী রহিম').click();
    await settle(page);
    await J.shot('রহিমের বাসা থেকে নমুনা — প্যাথলজির চোখে', 'ঠিকানা, ফোন, ‘চেয়েছেন: …’; ‘সময় নিশ্চিত করুন’ আর ‘বাসায় যেতে পারব না’।', { full: true });
    await page.getByRole('button', { name: 'বাসায় যেতে পারব না' }).click();
    const d = page.getByRole('dialog').last();
    await d.getByLabel('কারণ (ঐচ্ছিক)').fill('কাল লোক নেই');
    await d.getByRole('button', { name: 'যেতে পারব না' }).click();
    await settle(page, 600);
    await J.shot('বাসায় যাওয়া: না', 'অনুরোধ থাকে (‘নতুন’), ‘বাসায় যাওয়া: না’; ঠিকানা-ফোন আর দেখায় না।', { full: true });
    page = await J.as('patient');
    await openA222(page);
    await J.shot('রোগী: বাসায় আসবে না', 'লাল ‘বাসায় আসবে না — প্যাথলজিতে গিয়ে নমুনা দিন’; বাসার চার্জ যোগ হয় না।', { full: true });
  });

  await J.sc('S-75', 'মালিক পরীক্ষার ছক থেকে একটা পরীক্ষা লুকালে', async () => {
    page = await owner(J);
    await page.getByText('রিপোর্টের ছক', { exact: true }).first().click();
    await settle(page);
    await J.shot('অ্যাডমিন: রিপোর্টের ছক', 'পরীক্ষার তালিকা — ECG ইত্যাদি।', { full: true });
  });

  // ---------------- অ্যাম্বুলেন্স ----------------
  await J.sc('S-86', 'চালক: “এখনো খালি আছেন?” নোটিফিকেশন', async () => {
    page = await J.as('ambulance');
    await go(page, '/services/ambulance');
    await page.waitForTimeout(2500);
    await go(page, '/inbox');
    const has = await page.getByText(/এখনো খালি আছেন/).count();
    await J.shot('চালকের নোটিফিকেশন', '‘ডেমো জীবন মিনি অ্যাম্বুলেন্স — এখনো খালি আছেন?’ (ডেমো বানানোর আধা ঘণ্টার মধ্যে, আর মিনি তখনো চালু না হলে)।', has ? {} : { noteBn: 'এই চালানোয় মনে করানো আসেনি — আগের ধাপে (S-87) ‘আবার চালু রাখুন’ চাপা হয়েছিল, তাই মেয়াদের শেষ ঘণ্টায় নেই (নিয়ম অনুযায়ী ঠিক)।' });
  });

  await J.sc('S-85', 'চালক: গাড়ি যেখানে অপেক্ষা করে (GPS)', async () => {
    await go(page, '/work/ambulance');
    const c = page.locator('article, .card').filter({ hasText: 'ডেমো জীবন মিনি অ্যাম্বুলেন্স' }).filter({ has: page.getByRole('link', { name: /বদলান/ }).or(page.getByRole('button', { name: /বদলান/ })) }).first(); // "রোগীরা আপনাকে যেভাবে দেখেন"-এর কার্ড নয়
    await c.getByRole('link', { name: /বদলান/ }).or(c.getByRole('button', { name: /বদলান/ })).first().click();
    await settle(page);
    await page.getByRole('button', { name: 'বর্তমান লোকেশন ধরুন' }).click();
    await settle(page, 800);
    await J.shot('গাড়ির জায়গা নেওয়া হয়েছে', '‘গাড়ির জায়গা নেওয়া হয়েছে’।', { full: true });
    const terms = page.getByRole('checkbox').last();
    if (await terms.count()) await terms.check({ force: true }).catch(() => {});
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await see(page, 'সেভ হয়েছে');
    await J.shot('সেভ হয়েছে — যাচাইয়ে যায় না', '‘সেভ হয়েছে’; তালিকায় থাকে।');
  });

  // ---------------- ডাক্তার/প্যাথলজি/মালিক ----------------
  await J.sc('R-28 R-30', 'রোগীর CBC রিপোর্ট লেখা আর আগের রিপোর্ট দেখা', async () => {
    page = await J.as('lab');
    await labWork(page, 'পরীক্ষার অনুরোধ');
    const row = await orderRow(page, ['DEMXRXB333', 'CBC'], { button: 'রিপোর্ট দিন' });
    // ডেস্কটপে বাছলেই ডানে রিপোর্টের ঘর খোলে; ফোনে ‘রিপোর্ট দিন’ (নমুনা নেওয়া বাকি থাকলে "আরও"-তে)
    if (!(await page.getByRole('button', { name: 'রিপোর্ট পাঠান' }).count())) await rowAct(page, row, 'রিপোর্ট দিন');
    await settle(page);
    await page.getByLabel(/^Haemoglobin.*: ফল$/).first().fill('12.1');
    await page.getByLabel(/^Total WBC count.*: ফল$/).first().fill('12.5');
    await page.getByLabel(/^Platelet count.*: ফল$/).first().fill('250');
    await page.keyboard.press('Tab');
    await J.shot('রহিমের CBC — WBC বেশি', 'নিজের মেশিনের মাত্রা (Hb ১১.৫–১৬.৫); WBC ১২.৫ পাশে ‘বেশি’।', { full: true });
    await page.getByRole('button', { name: 'রিপোর্ট পাঠান' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'রোগীর কাছে রিপোর্ট গেছে');
    await labWork(page, 'পরীক্ষার অনুরোধ');
    // সেবার "আমার কাজ"-এর এক ছাঁচ: রিপোর্ট দেওয়া অনুরোধ "শেষ" ভাগে
    await page.getByRole('tab', { name: /^শেষ/ }).first().click();
    await settle(page);
    const r = page.locator('tr').filter({ hasText: 'Lipid profile, S. Creatinine' }).first();
    await r.getByRole('button', { name: 'দেখুন' }).click();
    await settle(page);
    await J.shot('আগের রিপোর্ট (R-30)', 'ফল, স্বাভাবিক মাত্রা, বেশি/কম, ‘আগে’ ঘরে আড়াই মাস আগের মান (Cholesterol ২৩৬ → ১৯৮)।', { full: true });
  });

  await J.sc('R-37', 'চেম্বারে ‘রোগীপ্রতি গড় সময়’', async () => {
    page = await J.as('doctor');
    await go(page, '/work');
    await tap(page, 'আমার চেম্বার');
    await tap(page, 'সেটআপ', { role: 'tab' });
    const avg = page.getByLabel('রোগীপ্রতি গড় সময় (মিনিট)');
    await avg.last().fill('15');
    await page.getByRole('button', { name: /সেভ/ }).last().click();
    await settle(page, 600);
    await go(page, '/work');
    await tap(page, 'আমার চেম্বার');
    await tap(page, 'সেটআপ', { role: 'tab' });
    await J.shot('গড় সময় ১৫ থাকে', 'কালিয়াকৈরে ‘রোগীপ্রতি গড় সময় (মিনিট)’ ১৫।', { full: true });
  });

  await J.sc('W-01', '‘আজকের কাজ’: অবস্থা, আজকের সংখ্যা, এখনই দেখুন; ফার্মেসিতে বিরতি → আবার খোলা', async () => {
    page = await J.as('doctor');
    await go(page, '/work');
    await page.getByTestId('today-home').waitFor({ timeout: 10000 });
    if (!(await has(page, 'এখনই দেখুন'))) throw new Error('‘এখনই দেখুন’ নেই');
    await J.shot('ডাক্তার: আজকের কাজ', 'উপরে পাতার নাম ‘আজকের কাজ’; ‘চেম্বার’ — আজ যে চেম্বার বসে তার ‘খোলা / বিরতি / বন্ধ’ আর ‘আজকের সিরিয়াল’ / ‘অপেক্ষায়’ (না বসলে ‘আজ চেম্বার নেই’); ‘এখনই দেখুন’; ‘আপনার কাজের পাতা’।', { full: true });
    page = await J.as('pharmacy');
    await go(page, '/work');
    await page.getByTestId('role-pharmacy').waitFor({ timeout: 10000 });
    const ctl = page.getByTestId('role-pharmacy').getByRole('group', { name: /অবস্থা/ });
    await ctl.getByRole('button', { name: /বিরতি/ }).click();
    await page.getByTestId('pause-options').getByRole('button', { name: '৩০ মিনিট' }).click();
    await settle(page, 800);
    await J.shot('ফার্মেসি: বিরতি ৩০ মিনিট', 'কমলা ‘বিরতি’ বাছা, নিচে ‘আবার খুলবে …’; রোগীর তালিকায় দোকান ‘বন্ধ’।');
    await ctl.getByRole('button', { name: /খোলা/ }).click();
    await settle(page, 800);
    if ((await ctl.getByRole('button', { name: /খোলা/ }).getAttribute('aria-pressed')) !== 'true') throw new Error('আবার ‘খোলা’ হয়নি');
    await J.shot('ফার্মেসি: আবার খোলা', '‘খোলা’ বাছা; ‘বিক্রি করুন’ এক চাপে।');
  });

  await J.sc('R-32 R-33', 'ব্যাজ সংশোধন মঞ্জুর, আর ডাক্তারের ব্যাজ বাতিল/ফেরত', async () => {
    page = await owner(J);
    await page.getByText('ব্যাজ', { exact: true }).first().click();
    await settle(page);
    await page.locator('tr').filter({ hasText: 'কামরুল' }).getByText('যাচাই করে মঞ্জুর').first().click();
    await settle(page);
    await J.shot('কামরুলের সংশোধন — যাচাই', 'নতুন রেজি. DEMO-A-55555, কর্মস্থল ‘ডেমো শিশু চেম্বার…’; নিচে আগেরটা।', { full: true });
    const boxes = page.locator('input[type=checkbox]:visible');
    for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check({ force: true }).catch(() => {});
    await page.getByRole('button', { name: /সংশোধন মঞ্জুর/ }).click();
    await settle(page, 600);
    await J.shot('সংশোধন মঞ্জুর', 'মঞ্জুরের পর ‘অনুমোদিত’-তে নতুন তথ্য।', { full: true });
    await page.getByRole('tab', { name: 'অনুমোদিত', exact: true }).first().click();
    await settle(page);
    await J.shot('অনুমোদিত ব্যাজ — পেশার নাম বাংলায়', 'জামালের পাশে ‘স্বাস্থ্য বিশেষজ্ঞ’ চিহ্ন।', { full: true, status: 'fixed', noteBn: 'আগে অ্যাডমিন প্যানেলে স্বাস্থ্য বিশেষজ্ঞের ব্যাজে ইংরেজি ‘expert’ লেখা দেখাত (পেশার বাংলা নামের তালিকায় ছিল না)। এখন ‘স্বাস্থ্য বিশেষজ্ঞ’।' });
    const row = page.locator('tr').filter({ hasText: 'নাসরিন আক্তার' }).first();
    await row.locator('input').first().fill('ডেমো: পরীক্ষার জন্য বাতিল');
    await row.getByRole('button', { name: /ব্যাজ বাতিল/ }).first().click();
    // বোতাম বদলে ‘নিশ্চিত? আবার চাপুন’
    await row.getByRole('button', { name: /নিশ্চিত\? আবার চাপুন/ }).first().click();
    await settle(page, 600);
    await J.shot('নাসরিনের ব্যাজ বাতিল', '‘ব্যাজ বাতিল করা হয়েছে, তালিকা থেকেও সরেছে’।', { full: true });
    await page.getByRole('tab', { name: 'বাতিল', exact: true }).first().click();
    await settle(page);
    await page.locator('tr').filter({ hasText: 'নাসরিন আক্তার' }).getByText('যাচাই করে অনুমোদন').first().click();
    await settle(page);
    const b2 = page.locator('input[type=checkbox]:visible');
    for (let i = 0; i < (await b2.count()); i++) await b2.nth(i).check({ force: true }).catch(() => {});
    await page.getByRole('button', { name: 'অনুমোদন দিন' }).click();
    await settle(page, 600);
    await J.shot('নাসরিন আবার অনুমোদিত', 'তালিকায় ফিরে আসেন (বাতিল হওয়া বুকিং ফেরে না)।', { full: true });
  });

  await J.sc('H-28', 'ব্যাজ বাতিলের আপিল আর ব্যবহারকারীর পুরো পাতা', async () => {
    await page.getByText('আপিল ও সমস্যা', { exact: true }).first().click();
    await settle(page);
    await page.locator('tr').filter({ hasText: 'মিঠু' }).first().click();
    await settle(page);
    await J.shot('মিঠুর আপিল', 'ব্যাজ বাতিলের আপিল।', { full: true });
    const all = page.getByText('এই ব্যবহারকারীর সব কিছু দেখুন ও ব্যবস্থা নিন');
    if (await all.count()) {
      await all.first().click();
      await settle(page);
      await J.shot('মিঠুর পুরো পাতা', '‘ব্যাজ: ফিটনেস ট্রেইনার’, রেজি. ‘ডেমো-FIT-0310’, বাতিল; পাশে ‘যাচাই করে অনুমোদন’।', { full: true });
    }
  });
}
