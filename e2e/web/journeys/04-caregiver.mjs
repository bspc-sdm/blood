// কেয়ারগিভার (গাইড journey-care.json J-60…J-69): রুমা রহিমের হয়ে — প্রেসক্রিপশন, সিরিয়াল, ফার্মেসি, প্যাথলজি, সীমা, সরিয়ে দেওয়া
import { pickQueueItem, tap, see, go, settle, dhakaISO, bookBtn, bookSent, pharmacyWork, labWorkTab, rxListAll } from '../lib.mjs';

export const meta = { no: 4, slug: 'caregiver', titleBn: 'কেয়ারগিভার: রোগীর হয়ে কাজ' };

const ME = 'ডেমো রোগী রহিম';

/** প্রোফাইল › কেয়ারগিভার ও পরিবার › রহিমের পাশে ‘ওঁর হয়ে খুলুন’ */
async function actFor(page) {
  await go(page, '/care');
  const row = page.locator('div, li, article').filter({ hasText: ME }).filter({ has: page.getByRole('button', { name: 'ওঁর হয়ে খুলুন' }) }).last();
  await row.getByRole('button', { name: 'ওঁর হয়ে খুলুন' }).click();
  await settle(page);
}

/** তালিকার কার্ডে কোড থাকে না — ওষুধ দিয়ে চেনা: DEMXRXB333 = Bislol-সহ, DEMXRXA222 = Secrin-সহ; পুরোনোগুলো "পূর্বের"-এ, তাই "সব" ট্যাবে */
async function openRxByCode(page, code) {
  await rxListAll(page, { soft: true });
  const key = { DEMXRXB333: /Bislol/, DEMXRXA222: /Secrin/ }[code] || code;
  await page.locator('article, [role=button]').filter({ hasText: key }).filter({ hasNotText: 'বাতিল' }).first().click();
  await settle(page);
}

export async function run(J) {
  let page;

  await J.sc('J-60', 'শুরু: রুমা রহিমের হয়ে খোলেন', async () => {
    page = await J.as('caregiver');
    await go(page, '/care');
    await J.shot('রুমা: কেয়ারগিভার ও পরিবার', '‘যাঁদের দেখাশোনা করি’-তে ‘ডেমো রোগী রহিম’ — ‘ওঁর হয়ে খুলুন’।', { full: true });
    await actFor(page);
    await see(page, 'হয়ে কাজ করছেন');
    await J.shot('রহিমের হয়ে কাজ — ফিতা', 'ওপরে ফিতা ‘ডেমো রোগী রহিম-এর হয়ে কাজ করছেন’; রহিমের প্রেসক্রিপশন (একটা ‘বাতিল’সহ)।', { full: true });
    await go(page, '/services/pharmacy/prescriptions', { soft: true });
    await J.shot('রহিমের প্রেসক্রিপশন (রুমার চোখে)', 'ওপরে ‘চলমান (n)’ / ‘পূর্বের (n)’ / ‘সব (n)’ — ‘চলমান’ খোলা; প্রতিটা কার্ডে এখন কোন অবস্থায়; রুমার নিজের নয়।', { full: true });
    await rxListAll(page, { soft: true });
    await J.shot('রহিমের সব প্রেসক্রিপশন', '‘সব’-এ ডা. ডেমো হাসানের সবগুলো, একটা ‘বাতিল’ (ধূসর, ‘বাতিল করেছেন ডাক্তার’)।', { full: true });
  });

  await J.sc('J-61', 'রোগীর অ্যাপয়েন্টমেন্ট দেখা আর বাতিল করা', async () => {
    await go(page, '/my-appointments', { soft: true });
    await J.shot('রহিমের অ্যাপয়েন্টমেন্ট (রুমার চোখে)', 'ডা. ডেমো হাসানের আগের ‘সম্পন্ন’ দুটো, অন্য ডাক্তারদের ‘অপেক্ষায়’/‘নিশ্চিত’/‘নেওয়া হয়নি’/‘বাতিল’/‘উত্তর আসেনি’।', { full: true });
    await page.locator('article').filter({ hasText: 'অপেক্ষায়' }).first().click();
    await settle(page);
    await J.shot('অপেক্ষায় থাকা অ্যাপয়েন্টমেন্ট — বাতিলের বোতাম', '‘অ্যাপয়েন্টমেন্ট বাতিল’ বোতাম আছে — রুমা রহিমের হয়ে বাতিল করতে পারেন।');
    await see(page, 'অ্যাপয়েন্টমেন্ট বাতিল');
  });

  await J.sc('J-62', 'রোগীর হয়ে সিরিয়াল — প্রেসক্রিপশন থেকে', async () => {
    await openRxByCode(page, 'DEMXRXB333');
    await J.shot('DEMXRXB333 — রক্তচাপ-ডায়াবেটিসের প্রেসক্রিপশন', 'রোগনির্ণয় ‘Type 2 DM, Dyslipidaemia, Hypertension’; নিচে ‘আবার ডাক্তার দেখানো’ — ‘সিরিয়াল নিন’।', { full: true });
    await tap(page, 'সিরিয়াল নিন', { role: 'button' });
    await settle(page, 500);
    await J.shot('সিরিয়াল চাওয়া হলো', '‘অনুরোধ গেছে। ডাক্তার নিশ্চিত করলে নোটিফিকেশন পাবেন।’ (বা আগে থাকলে ‘সেদিন … আগে থেকেই আছে’)।');
    await go(page, '/my-appointments', { soft: true });
    await J.shot('রহিমের নামে নতুন অনুরোধ', 'ডা. ডেমো হাসানের নতুন অনুরোধ ‘অপেক্ষায়’ — রহিমের নামে।', { full: true });
  });

  await J.sc('J-63', 'রোগীর হয়ে ডাক্তারের তালিকা থেকে সিরিয়াল (ওয়েব)', async () => {
    await go(page, '/appointment', { soft: true });
    await tap(page, 'ডা. ডেমো হাসান');
    await bookBtn(page).first().click();
    const d = page.getByRole('dialog').last();
    await J.shot('কার জন্য — রহিমের পরিবার', '‘কার জন্য’-তে ‘আমি’ (= রহিম), ‘ডেমো মা রাবেয়া বেগম’, ‘ডেমো ছেলে তামিম’ — রুমার নিজের নয়।');
    // কাল: পরশু (বা তার পরের অনলাইন দিনে) রহিমের এই ডাক্তারের অনলাইন চেম্বারে নিশ্চিত সিরিয়াল আছে (ডেমো, online_chamber চালু হলে) — একই দিনে
    // একই ডাক্তারের কাছে দ্বিতীয় অনুরোধ হয় না (‘সেদিন … আগে থেকেই আছে’)
    await d.locator('input[type=date]').fill(dhakaISO(1));
    await d.getByRole('textbox').last().fill('ডেমো: রুমা রহিমের হয়ে সিরিয়াল নিলেন');
    await d.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
    await bookSent(page, J);
    await J.shot('রহিমের হয়ে অনুরোধ গেল', '‘অনুরোধ পাঠানো হয়েছে…’।');
    await bookBtn(page).first().click();
    const d2 = page.getByRole('dialog').last();
    await d2.getByText('ডেমো মা রাবেয়া বেগম').click();
    await d2.locator('input[type=date]').fill(dhakaISO(3));
    await d2.getByRole('textbox').last().fill('ডেমো: রুমা রাবেয়ার হয়ে সিরিয়াল নিলেন');
    await J.shot('রাবেয়ার জন্য অনুরোধ', '‘কার জন্য’-তে ‘ডেমো মা রাবেয়া বেগম’।');
    await d2.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
    await bookSent(page);
  });

  await J.sc('J-64', 'ডাক্তার রুমার নেওয়া অনুরোধটা রহিমের নামে দেখেন', async () => {
    page = await J.as('doctor');
    await go(page, '/work');
    await tap(page, 'আমার চেম্বার');
    await tap(page, 'বুকিং', { role: 'tab' });
    await tap(page, /^নতুন/, { role: 'tab' });
    await pickQueueItem(page, 'রুমা রহিমের');
    await J.shot('ডাক্তার: নতুন — রহিম আর রাবেয়া', 'রোগী ‘ডেমো রোগী রহিম’ (সমস্যা ‘ডেমো: রুমা রহিমের হয়ে সিরিয়াল নিলেন’) আর ‘ডেমো মা রাবেয়া বেগম’।', { full: true });
    const row = page.locator('tr, article').filter({ hasText: 'রুমা রহিমের হয়ে' }).first();
    await row.getByRole('button', { name: 'নিশ্চিত করুন' }).click();
    const d = page.getByRole('dialog').last();
    await d.getByRole('button', { name: 'নিশ্চিত করুন' }).click();
    await settle(page, 600);
    await J.shot('রহিমের অনুরোধ নিশ্চিত', 'সারি ‘নিশ্চিত’, সিরিয়াল নম্বর।');
    page = await J.as('caregiver');
    await actFor(page);
    await go(page, '/my-appointments', { soft: true });
    await J.shot('রুমার চোখে — নিশ্চিত, সিরিয়াল নম্বর', 'রুমার ‘সিরিয়াল’-এ সেটা ‘নিশ্চিত’, সিরিয়াল নম্বরসহ।', { full: true });
  });

  await J.sc('J-65', 'প্রেসক্রিপশন আর ল্যাব রিপোর্ট দেখা', async () => {
    await openRxByCode(page, 'DEMXRXB333');
    await J.shot('DEMXRXB333 — ওষুধ কেনা আর পরীক্ষা', 'কেনা: Comet, Rosuva (ডেমো লাইফ ফার্মেসি); বাকি Bislol, Napa; Lipid profile আর S. Creatinine ‘রিপোর্ট এসেছে’, CBC নমুনা নেওয়া।', { full: true });
    const rep = page.getByRole('button', { name: 'রিপোর্ট দেখুন' }).first();
    if (await rep.count()) {
      await rep.click();
      await settle(page);
      await J.shot('Lipid profile, S. Creatinine — রিপোর্ট', 'প্রতিটা মান, একক, স্বাভাবিক মাত্রা; অস্বাভাবিকগুলো আলাদা রঙে।');
      await page.keyboard.press('Escape');
    }
    await openRxByCode(page, 'DEMXRXA222');
    await J.shot('DEMXRXA222 — ডাক্তারের মন্তব্য', 'ডাক্তারের মন্তব্য ‘সুগার আর কোলেস্টেরল বেশি…’ (দেখা হয়েছে)।', { full: true });
  });

  await J.sc('J-66', 'রোগীর হয়ে বাকি ওষুধ ফার্মেসিতে পাঠানো ও ফিরিয়ে নেওয়া', async () => {
    await openRxByCode(page, 'DEMXRXB333');
    await page.getByRole('button', { name: 'ফার্মেসিতে পাঠান' }).last().click();
    await tap(page, 'দোকানে আছি — এই দোকানে পাঠান');
    await settle(page);
    const d = page.getByRole('dialog').last();
    await J.shot('কাছের দোকান', 'কাছের দোকানের তালিকায় ‘ডেমো লাইফ ফার্মেসি’ — ‘এই দোকানে দিন’।');
    await d.locator('article, .card').filter({ hasText: 'ডেমো লাইফ ফার্মেসি' }).getByRole('button', { name: 'এই দোকানে দিন' }).first().click();
    await see(page, 'ডেমো লাইফ ফার্মেসি-এ পাঠানো হয়েছে');
    await J.shot('দোকানে পাঠানো হলো', '‘ডেমো লাইফ ফার্মেসি-এ পাঠানো হয়েছে’; ‘পাঠানো হয়েছে — অপেক্ষায়’।', { full: true });
    page = await J.as('pharmacy');
    await pharmacyWork(page);
    await J.shot('ফার্মেসি: আপনার কাছে পাঠানো — রহিম', 'রোগীর নাম ‘ডেমো রোগী রহিম’।', { full: true });
    page = await J.as('caregiver');
    await actFor(page);
    await openRxByCode(page, 'DEMXRXB333');
    await tap(page, 'ফিরিয়ে নিন', { role: 'button' });
    const c = page.getByRole('dialog');
    if (await c.count()) await c.last().getByRole('button', { name: /ফিরিয়ে নিন|হ্যাঁ/ }).last().click();
    await see(page, 'ফিরিয়ে নেওয়া হয়েছে');
    await J.shot('ফিরিয়ে নেওয়া হলো', '‘ফিরিয়ে নেওয়া হয়েছে। অন্য ফার্মেসিতে পাঠাতে পারেন।’');
  });

  await J.sc('J-67 S-69', 'রোগীর হয়ে পরীক্ষা প্যাথলজিতে পাঠানো', async () => {
    await openRxByCode(page, 'DEMXRXA222');
    await tap(page, 'পরীক্ষা করাতে পাঠান', { role: 'button' });
    await settle(page);
    const d = page.getByRole('dialog').last();
    await J.shot('প্যাথলজি বাছুন (রহিমের হয়ে)', 'HbA1c, FBS, Lipid profile — প্যাথলজির তালিকা আর আনুমানিক দাম।');
    await d.locator('article, .card').filter({ hasText: 'ডেমো সিটি ডায়াগনস্টিক সেন্টার' }).getByRole('button', { name: 'পরীক্ষা পাঠান' }).first().click();
    const h = page.getByRole('dialog').last();
    // বাসা থেকে নমুনা (S-69-এর মতো) — ফোন রোগীর আগের অনুরোধ থেকে বসানো থাকে, কেয়ারগিভারের নিজের নয়
    await h.getByText('বাসা থেকে নমুনা নিতে আসবে').click();
    await h.getByLabel('বাসার ঠিকানা').fill('ডেমো বাসা ২, শিববাড়ি');
    const ph = h.getByLabel('মোবাইল নম্বর');
    await J.shot('রহিমের হয়ে বাসা থেকে নমুনা (S-69)', 'আনুমানিক দাম; ঠিকানা আর ফোন রোগীর আগের অনুরোধ থেকে (কেয়ারগিভারের নিজের নম্বর নয়)।');
    if (!(await ph.inputValue())) await ph.fill('01200001101');
    await h.getByText('পরশু').first().click().catch(() => {});
    await h.getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'পাঠানো হয়েছে');
    await J.shot('পরীক্ষা পাঠানো হলো', '‘…পাঠানো হয়েছে’; অবস্থা ‘পাঠানো হয়েছে — অপেক্ষায়’ (HbA1c, FBS, Lipid profile)।', { full: true });
    await openRxByCode(page, 'DEMXRXB333');
    await tap(page, 'পরীক্ষা করাতে পাঠান', { role: 'button' });
    await settle(page);
    const d2 = page.getByRole('dialog').last();
    await d2.locator('article, .card').filter({ hasText: 'ডেমো সিটি ডায়াগনস্টিক সেন্টার' }).getByRole('button', { name: 'পরীক্ষা পাঠান' }).first().click();
    const h2 = page.getByRole('dialog').last();
    const lab2 = h2.getByText('প্যাথলজিতে গিয়ে দেব');
    if (await lab2.count()) { await lab2.click(); await h2.getByRole('button', { name: 'পাঠান', exact: true }).click(); }
    await settle(page, 500);
    await J.shot('একই প্যাথলজিতে আবার — আটকায়', '‘এই প্যাথলজিতে আগেই পাঠানো আছে’।');
    await page.keyboard.press('Escape');
    page = await J.as('lab');
    await labWorkTab(page);
    await J.shot('প্যাথলজি: রহিমের নতুন অনুরোধ', 'রোগী ‘ডেমো রোগী রহিম’-এর নামে নতুন অনুরোধ।', { full: true });
  });

  await J.sc('J-68', 'কেয়ারগিভার যা রোগীর হয়ে পারেন না', async () => {
    page = await J.as('caregiver');
    await actFor(page);
    await go(page, '/profile', { soft: true });
    await J.shot('ফিতা চালু রেখে প্রোফাইল — রুমার নিজের', 'প্রোফাইল রুমার নিজের (রহিমের নয়)।', { full: true });
    await go(page, '/inbox', { soft: true });
    await J.shot('ফিতা চালু রেখে নোটিফিকেশন — রুমার নিজের', 'রুমার নিজের ঘণ্টা; রহিমের প্রেসক্রিপশন/রিপোর্টের খবর ‘ডেমো রোগী রহিম: …’ হয়ে।', { full: true });
    await go(page, '/services/pharmacy/medicine', { soft: true });
    await J.shot('ফিতা চালু রেখে ওষুধ খুঁজুন — রুমার নামে', '‘ওষুধ খুঁজুন’ রুমার নিজের নামে।');
  });

  await J.sc('J-69 H-20', 'রোগী সরিয়ে দিলে যাত্রা বন্ধ', async () => {
    const rumaPage = page;
    const P = await J.ctxFor('desktop');
    J.cur = P;
    const pt = await J.as('patient', 'desktop');
    await go(pt, '/care');
    const row = pt.locator('div, li, article').filter({ hasText: 'ডেমো কেয়ারগিভার রুমা' }).filter({ has: pt.getByRole('button', { name: 'সরিয়ে দিন' }) }).last();
    await row.getByRole('button', { name: 'সরিয়ে দিন' }).click();
    const d = pt.getByRole('dialog').last();
    await d.getByRole('button', { name: 'হ্যাঁ' }).click();
    await settle(pt, 500);
    await J.shot('রহিম রুমাকে সরিয়ে দিলেন', 'রুমা ‘আমার কেয়ারগিভার’ থেকে সরে যান।', { page: pt });
    J.cur = Object.values(J.pages).find((x) => x.page === rumaPage);
    await go(rumaPage, '/my-appointments', { soft: true });
    await settle(rumaPage, 800);
    await J.shot('রুমার পাতায় — আর কেয়ারগিভার নন', '‘আপনি আর এই মানুষের কেয়ারগিভার নন’, ফিতা চলে যায়।', { page: rumaPage, full: true });
    J.cur = P;
    await go(pt, '/my-appointments');
    await J.shot('রহিমের নিজের তালিকায় রুমার কাজগুলো থাকে', 'রুমা যা করেছিলেন (সিরিয়াল) সব রহিমের তালিকায়।', { page: pt, full: true });

    // আবার চালু (গাইড J-69-এর শেষ লাইন): রহিম রুমাকে ইমেইল দিয়ে যোগ করেন, রুমা ‘রাজি’
    await go(pt, '/care');
    await pt.getByRole('button', { name: 'কেয়ারগিভার যোগ করুন' }).click();
    const ad = pt.getByRole('dialog').last();
    await ad.getByLabel('ইমেইল').fill('caregiver@lifolo-demo.test');
    await ad.getByRole('button', { name: 'পাঠান' }).click();
    await see(pt, 'পাঠানো হয়েছে। তিনি রাজি হলে চালু হবে।');
    await J.shot('রহিম আবার রুমাকে যোগ করলেন', '‘পাঠানো হয়েছে। তিনি রাজি হলে চালু হবে।’; রুমা ‘রাজি হওয়ার অপেক্ষায়’।', { page: pt, full: true });
    J.cur = Object.values(J.pages).find((x) => x.page === rumaPage);
    await go(rumaPage, '/care');
    const rr = rumaPage.locator('div, li, article').filter({ hasText: ME }).filter({ has: rumaPage.getByRole('button', { name: 'রাজি' }) }).last();
    await rr.getByRole('button', { name: 'রাজি' }).click();
    await settle(rumaPage, 500);
    await J.shot('রুমা রাজি — আবার চালু', '‘যাঁদের দেখাশোনা করি’-তে রহিম, পাশে ‘ওঁর হয়ে খুলুন’।', { page: rumaPage, full: true });
  });
}
