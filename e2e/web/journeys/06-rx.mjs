// ডাক্তার ও প্রেসক্রিপশন (গাইড rx.json R-01…R-38): তালিকা, বুকিং, সেটআপ, রোগী-পাতা, সহকারী, রিপোর্ট দেখানো, ফার্মেসি/প্যাথলজির দিক, যাচাই
import { tap, see, go, settle, dhakaISO, logout, bookBtn, pharmacyWork, labWorkTab, orderRow } from '../lib.mjs';
import { BASE } from '../stack.mjs';
import { pickDistrict } from './02-blood.mjs';
import { adminLogin } from './00-seed.mjs';

export const meta = { no: 6, slug: 'prescription', titleBn: 'ডাক্তার, প্রেসক্রিপশন, ফার্মেসি আর প্যাথলজির দিক' };

async function area(page) {
  const choose = page.getByRole('button', { name: /এলাকা বেছে নিন/ }).first();
  if (await choose.count()) { await choose.click(); await pickDistrict(page); }
}
async function rxWork(page, tab) {
  await go(page, '/work');
  await page.locator('a, .card').filter({ hasText: 'প্রেসক্রিপশন' }).filter({ hasText: /রোগী|সহকারী/ }).first().click();
  await settle(page);
  if (tab) await tap(page, tab, { role: 'tab', exact: true });
}
async function chamber(page, tab) {
  await go(page, '/work');
  await tap(page, 'আমার চেম্বার');
  if (tab) await tap(page, tab, { role: 'tab' });
}
async function shop(page, tab) {
  await pharmacyWork(page, tab);
}
async function labWork(page) {
  await labWorkTab(page);
}
async function openCode(page, code) {
  const box = page.getByPlaceholder(/QR-এর নিচের কোড/).first();
  await box.fill(code);
  await page.getByRole('button', { name: 'খুলুন' }).first().click();
  await settle(page, 500);
}

export async function run(J) {
  let page;

  await J.sc('R-01', 'লগইন ছাড়া ডাক্তারের তালিকা', async () => {
    page = await J.as('guest');
    await go(page, '/appointment');
    await area(page);
    await J.shot('অতিথি: ডাক্তারের তালিকা', 'হাসান (‘অ্যাপে অ্যাপয়েন্টমেন্ট’, ‘আরও ১টা চেম্বার’), নাসরিন, কামরুল, মাহবুব, ফারজানা; তানভীর নেই।', { full: true });
  });

  await J.sc('R-02', 'ডাক্তারের বিস্তারিত, ফিল্টার আর শুধু-ফোনের ডাক্তার', async () => {
    await tap(page, 'ডা. ডেমো হাসান');
    await J.shot('হাসান — দুটো চেম্বার', 'মেডিকেল (বহির্বিভাগ ৩১২, প্রতিদিন, ফি ৫০০) আর কালিয়াকৈর (শুক্রবার, ফি ৪০০)।', { full: true });
    await go(page, '/appointment');
    // ২.৩৩.০: বিশেষজ্ঞতা তালিকার ওপরেই চিপ (আগে ফিল্টারের জানালায়)
    const chip = page.getByRole('button', { name: 'হৃদরোগ', exact: true });
    if (await chip.count()) {
      await chip.first().click();
      await settle(page);
    } else {
      await tap(page, 'ফিল্টার');
      const d = page.getByRole('dialog').last();
      // ফিল্টারের জানালায় চিপে সংখ্যাও থাকে: ‘হৃদরোগ (১)’ (দ্রুত-চিপ শুধু সবচেয়ে বেশি ডাক্তারের ৩টা বিশেষজ্ঞতা — নতুন পেশা আর
      // চিকিৎসা পদ্ধতির ডাক্তার যোগ হলে হৃদরোগ সেখানে থাকে না)
      await d.getByText(/^হৃদরোগ( \(|$)/).first().click();
      const show = d.getByRole('button', { name: 'দেখান' });
      if (await show.count()) await show.click();
      await settle(page);
    }
    await J.shot('ফিল্টার: হৃদরোগ', 'শুধু ‘ডা. ডেমো মাহবুব আলম’।', { full: true });
    if (await chip.count()) {
      await chip.first().click();
      await settle(page);
    } else {
      const x = page.getByRole('button', { name: /সরান/ });
      if (await x.count()) await x.first().click();
    }
    await tap(page, 'ডা. ডেমো ফারজানা হক');
    await J.shot('ফারজানা — শুধু ফোনে সিরিয়াল', '‘অ্যাপয়েন্টমেন্ট চাই’ নেই, শুধু ‘সিরিয়ালের জন্য ফোন’।', { full: true });
  });

  await J.sc('R-03', 'লগইন ছাড়া অ্যাপয়েন্টমেন্ট চাওয়া যায় না', async () => {
    await go(page, '/appointment');
    await tap(page, 'ডা. ডেমো হাসান');
    await bookBtn(page).first().click();
    await settle(page);
    await J.shot('অতিথি: অ্যাপয়েন্টমেন্ট চাই — লগইন', '‘অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন’ — লগইনের ঘর।');
    await see(page, 'অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন');
    await page.keyboard.press('Escape');
  });

  await J.sc('R-04', 'রোগীর আগের/সামনের অ্যাপয়েন্টমেন্টের সব অবস্থা', async () => {
    page = await J.as('patient');
    await go(page, '/my-appointments');
    await J.shot('আমার অ্যাপয়েন্টমেন্ট — সব অবস্থা', 'সামনের: মাহবুব (নিশ্চিত, সিরিয়াল ২), কামরুল (অপেক্ষায়); আগের: হাসান সম্পন্ন দুটো, মাহবুব উত্তর আসেনি, কামরুল নেওয়া হয়নি, নাসরিন বাতিল।', { full: true });
  });

  await J.sc('R-05', 'রোগী: অপেক্ষার অনুরোধ বাতিল', async () => {
    await page.locator('article').filter({ hasText: 'ডা. ডেমো কামরুল ইসলাম' }).filter({ hasText: 'অপেক্ষায়' }).first().click();
    await settle(page);
    await page.getByRole('button', { name: 'অ্যাপয়েন্টমেন্ট বাতিল' }).first().click();
    const d = page.getByRole('dialog');
    if (await d.count()) await d.last().getByRole('button', { name: 'অ্যাপয়েন্টমেন্ট বাতিল' }).click();
    await see(page, 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে');
    await J.shot('অপেক্ষার অনুরোধ বাতিল', '‘অ্যাপয়েন্টমেন্ট বাতিল হয়েছে’; কার্ড ‘আগের’-এ ‘বাতিল’।', { full: true });
  });

  await J.sc('R-06', 'ভুল বুকিং আটকায়', async () => {
    await go(page, '/appointment');
    await tap(page, 'ডা. ডেমো হাসান');
    await bookBtn(page).nth(1).click();
    let d = page.getByRole('dialog').last();
    // শুক্রবার নয় এমন দিন
    let day = 1;
    for (; day < 8; day++) { const dt = new Date(dhakaISO(day)); if (dt.getUTCDay() !== 5) break; }
    await d.locator('input[type=date]').fill(dhakaISO(day));
    await d.getByRole('textbox').last().fill('ডেমো: পরীক্ষা');
    await d.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
    await settle(page, 300);
    await J.shot('সেদিন চেম্বার বন্ধ', '‘সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।’');
    let fri = 1;
    for (; fri < 8; fri++) { if (new Date(dhakaISO(fri)).getUTCDay() === 5) break; }
    await d.locator('input[type=date]').fill(dhakaISO(fri));
    await d.getByRole('textbox').last().fill('');
    await d.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
    await settle(page, 300);
    await J.shot('কী সমস্যা খালি', '‘কী সমস্যা, সংক্ষেপে লিখুন’।');
    await page.keyboard.press('Escape');
    await bookBtn(page).first().click();
    d = page.getByRole('dialog').last();
    await d.locator('input[type=date]').fill(dhakaISO(0));
    await d.getByRole('textbox').last().fill('আবার');
    await d.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
    await settle(page, 400);
    await J.shot('একই দিনে আবার', '‘সেদিন এই ডাক্তারের কাছে আপনার অনুরোধ আগে থেকেই আছে’।');
    await page.keyboard.press('Escape');
  });

  await J.sc('R-08', 'ওয়েবে সিরিয়ালের সারি (কম্পিউটার)', async () => {
    page = await J.as('doctor');
    await chamber(page, 'বুকিং');
    for (const t of [/^অপেক্ষায়/, /^সামনের/, /^আগের/]) {
      await tap(page, t, { role: 'tab' });
      await J.shot(`বুকিং — ${String(t).replace(/[\/^]/g, '')}`, 'ছকে সিরিয়াল, রোগী, তারিখ, সময়, অবস্থা, ফোন।', { full: true });
    }
    await tap(page, /^সামনের/, { role: 'tab' });
    const row = page.locator('tr').filter({ hasText: 'ডেমো রোগী নাসিমা বেগম' }).first();
    await row.getByRole('button', { name: 'অ্যাপয়েন্টমেন্ট বাতিল' }).click();
    const d = page.getByRole('dialog').last();
    await d.getByRole('button', { name: /বাতিল/ }).last().click();
    await settle(page, 600);
    await J.shot('নাসিমার অ্যাপয়েন্টমেন্ট বাতিল', '‘অ্যাপয়েন্টমেন্ট বাতিল হয়েছে’।', { full: true });
  });

  await J.sc('R-09', 'রোগীর তালিকা', async () => {
    await chamber(page, 'রোগী');
    await J.shot('চেম্বার › রোগী', 'অ্যাপে আসা রোগী — কতবার, শেষ কবে; নিশ্চিত অ্যাপয়েন্টমেন্ট থাকলে ফোন।', { full: true });
  });

  await J.sc('R-10', 'চেম্বারের সেটআপ বদলানো', async () => {
    await chamber(page, 'সেটআপ');
    await J.shot('চেম্বার › সেটআপ', 'দুটো চেম্বার; প্রথমটায় সকাল আর বিকাল (প্রতিদিন); ‘অ্যাপে অ্যাপয়েন্টমেন্ট’; ‘অনলাইনে রিপোর্ট দেখা’।', { full: true });
    const fees = page.getByLabel(/ফি/);
    if ((await fees.count()) > 1) {
      await fees.nth(1).fill('৪৫০ টাকা');
      await page.getByRole('button', { name: /সেভ/ }).last().click();
      await settle(page, 600);
      await J.shot('কালিয়াকৈরের ফি ৪৫০', 'সেভ হয়েছে; রোগীর তালিকায় ৪৫০ টাকা।');
    }
  });

  await J.sc('R-11', 'ডাক্তারের রোগী-পাতা (ওয়েব)', async () => {
    await rxWork(page, 'রোগী');
    await page.locator('tr').filter({ hasText: 'ডেমো রোগী জমিলা খাতুন' }).first().getByText('ডেমো রোগী জমিলা খাতুন').click();
    await settle(page);
    await J.shot('জমিলা খাতুন — আগের প্রেসক্রিপশন', '১০ দিন আগের (Osteoarthritis knee)।', { full: true });
    await page.getByPlaceholder('নাম বা ফোন দিয়ে খুঁজুন').fill('করিম');
    await settle(page, 400);
    await J.shot('খোঁজ: করিম', 'শুধু আব্দুল করিম।', { full: true });
  });

  await J.sc('R-13', 'আমার লেখা প্রেসক্রিপশন, ছাপা আর বাতিল', async () => {
    await rxWork(page, 'প্রেসক্রিপশন');
    await J.shot('প্রেসক্রিপশনের তালিকা', 'আজকের সালমা আর আব্দুল করিম (DEMXRXK777), রহিমের DEMXRXB333, DEMXRXA222, বাতিল DEMXRXC444, জমিলা…; অ্যাপের রোগীর পাশে ‘অ্যাপে’।', { full: true });
    await page.locator('tr').filter({ hasText: 'ডেমো রোগী জমিলা খাতুন' }).first().click();
    await settle(page);
    await page.emulateMedia({ media: 'print' });
    await J.shot('ছাপার পাতা', 'ডাক্তার, চেম্বার, রেজি. DEMO-A-10001, QR।', { full: true });
    await page.emulateMedia({ media: 'screen' });
    await tap(page, 'এই প্রেসক্রিপশন বাতিল করুন');
    const d = page.getByRole('dialog').last();
    await d.getByRole('button', { name: 'বাতিল করুন' }).click();
    await see(page, 'বাতিল করা হয়েছে');
    await J.shot('প্রেসক্রিপশন বাতিল', '‘বাতিল করা হয়েছে’।', { full: true });
  });

  await J.sc('R-16', 'ডাক্তার: রিপোর্টের তালিকা আর জবাব (সালমা)', async () => {
    await rxWork(page, 'রিপোর্ট');
    await tap(page, 'দেখতে হবে', { role: 'tab' }).catch(() => tap(page, 'দেখতে হবে'));
    const row = page.locator('tr, article, .card').filter({ hasText: 'ডেমো রোগী সালমা খাতুন' }).filter({ has: page.getByRole('button', { name: 'রিপোর্ট দেখে জানান' }) }).first();
    await row.getByRole('button', { name: 'রিপোর্ট দেখে জানান' }).click();
    const d = page.getByRole('dialog').last();
    await J.shot('সালমার রিপোর্ট', 'TSH 9.8 ‘বেশি’, Haemoglobin 10.9 ‘কম’, ESR 28 ‘বেশি’।');
    await d.getByLabel('রোগীকে কী জানাবেন?').fill('TSH বেশি, Thyrox 75 চলবে।');
    await d.getByRole('button', { name: 'রোগীকে পাঠান' }).click();
    await settle(page, 600);
    await J.shot('সালমাকে জানানো হলো', '‘দেখা হয়েছে’-তে চলে যায়।', { full: true });
  });

  await J.sc('R-17', 'অনলাইনে রিপোর্ট দেখার সেটিং', async () => {
    await tap(page, 'সেটিং বদলান');
    const d = page.getByRole('dialog').last();
    await J.shot('অনলাইনে রিপোর্ট দেখা — সেটিং', 'চালু, ফি ৩০০ টাকা, নম্বর ০১২০০০০০২০২, bKash/Nagad।');
    const num = d.getByLabel('যে নম্বরে টাকা নেবেন');
    const old = await num.inputValue();
    await num.fill('');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 300);
    await J.shot('নম্বর খালি — আটকায়', '‘টাকা পাঠানোর মোবাইল নম্বর দিন’।');
    await num.fill(old);
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 400);
  });

  await J.sc('R-18', 'টিম: সহকারী সরানো ও আবার যোগ', async () => {
    await rxWork(page, 'সহকারী');
    await J.shot('সহকারী', 'assistant@lifolo-demo.test — ‘সরান’; ‘সহকারীর ইমেইল’ ঘর।', { full: true });
    const add = page.getByLabel('সহকারীর ইমেইল');
    await add.fill('doctor@lifolo-demo.test');
    await page.getByRole('button', { name: 'যোগ', exact: true }).click();
    await settle(page, 400);
    await J.shot('নিজেকে সহকারী — আটকায়', '‘নিজেকে সহকারী হিসেবে যোগ করা যায় না’।');
  });

  await J.sc('R-14', 'সহকারী: রোগী এন্ট্রি (লিখতে পারেন না)', async () => {
    page = await J.as('assistant');
    await rxWork(page, 'রোগী');
    await page.getByRole('button', { name: 'নতুন রোগী' }).click();
    const d = page.getByRole('dialog').last();
    await d.getByLabel('নাম').fill('ডেমো রোগী নতুন');
    await d.getByLabel('বয়স (বছর)').fill('30');
    await d.getByText('পুরুষ', { exact: true }).click();
    await d.getByLabel('মোবাইল নম্বর').fill('01200000399');
    await J.shot('নতুন রোগী এন্ট্রি', 'নাম ‘ডেমো রোগী নতুন’, ৩০, পুরুষ, ০১২০০০০০৩৯৯।');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await see(page, 'রোগী যোগ হয়েছে');
    await J.shot('রোগী যোগ হলো', '‘রোগী যোগ হয়েছে’; ‘এন্ট্রি করা রোগী’-তে নতুন নাম।', { full: true });
    await page.getByRole('button', { name: 'নতুন রোগী' }).click();
    const d2 = page.getByRole('dialog').last();
    await d2.getByLabel('নাম').fill('ডেমো রোগী নতুন');
    await d2.getByLabel('বয়স (বছর)').fill('30');
    await d2.getByLabel('মোবাইল নম্বর').fill('01200000399');
    await d2.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 400);
    await J.shot('একই রোগী আবার', '‘এই রোগী আগে থেকেই ছিলেন’।');
    await page.keyboard.press('Escape');
  });

  await J.sc('R-15', 'সহকারী: রিপোর্ট দেখার টাকা মেলানো', async () => {
    await rxWork(page, 'টাকা মেলানো');
    await J.shot('টাকা মেলানো', 'রহিম (Lipid profile, S. Creatinine · TrxID DEMO7K3P9Q) — ‘টাকা পেয়েছি’ / ‘মেলেনি’।', { full: true });
    const row = page.locator('tr, article, .card').filter({ hasText: 'DEMO7K3P9Q' }).first();
    await row.getByRole('button', { name: 'টাকা পেয়েছি' }).click();
    await settle(page, 600);
    await J.shot('টাকা মিলেছে', '‘নিশ্চিত হয়েছে’।', { full: true });
  });

  await J.sc('R-19', 'আমার প্রেসক্রিপশনের তালিকা', async () => {
    page = await J.as('patient');
    await go(page, '/services/pharmacy/prescriptions');
    await J.shot('আমার প্রেসক্রিপশন', 'ডা. ডেমো হাসানের কয়েকটা (A222, B333, বাতিল C444, যাত্রার নতুনটা) আর অনলাইন অর্ডারের প্রেসক্রিপশন।', { full: true });
    await page.locator('article').filter({ hasText: /Bislol/ }).filter({ hasNotText: 'বাতিল' }).first().click();
    await settle(page);
    await J.shot('DEMXRXB333', 'Comet ‘কেনা ৩০/৬০’, Rosuva ‘৩০/৩০’, Bislol আর Napa ‘এখনো কেনা হয়নি’; Lipid + Creatinine ‘রিপোর্ট এসেছে’, CBC ‘নমুনা নেওয়া হয়েছে’; ‘আবার ডাক্তার দেখানো’।', { full: true });
  });

  await J.sc('R-20', 'কাগজের প্রেসক্রিপশন কোড দিয়ে যোগ', async () => {
    await go(page, '/services/pharmacy/prescriptions');
    await tap(page, 'কোড দিয়ে যোগ করুন');
    const d = page.getByRole('dialog').last();
    const inputs = d.getByRole('textbox');
    await inputs.nth(0).fill('DEMXPAPER6');
    await inputs.nth(1).fill('01799999999');
    await d.getByRole('button', { name: 'যোগ করুন' }).click();
    await settle(page, 400);
    await J.shot('ভুল নম্বরে আটকায়', '‘প্রোফাইলের বা পরিবারের সদস্যের মোবাইল নম্বরটাই দিন’।');
    await inputs.nth(1).fill('01200001101');
    await d.getByRole('button', { name: 'যোগ করুন' }).click();
    await settle(page, 600);
    await J.shot('কাগজের প্রেসক্রিপশন যোগ হলো', '‘১টা প্রেসক্রিপশন যোগ হয়েছে’ — Acute URTI (Fexo, Napa; RBS)।', { full: true });
  });

  await J.sc('R-21', 'লগইন ছাড়া প্রেসক্রিপশন আসল কি না যাচাই', async () => {
    const P = await J.ctxFor('desktop');
    J.cur = P;
    const g = P.page;
    for (const [code, exp] of [['DEMXRXB333', '‘আসল · Genuine’ — ডা. ডেমো হাসান, BMDC DEMO-A-10001, রোগী, ওষুধ ও পরীক্ষা (ফোন/রোগনির্ণয় নেই)।'], ['DEMXRXC444', '‘বাতিল · Cancelled’।'], ['DEMXRXZZZZ', '‘পাওয়া যায়নি’।']]) {
      await g.goto(`${BASE}/rx/${code}`);
      await settle(g);
      await J.shot(`যাচাই: /rx/${code}`, exp, { full: true });
    }
  });

  await J.sc('R-22', 'রোগী: দোকানে দাঁড়িয়ে প্রেসক্রিপশন পাঠানো', async () => {
    page = await J.as('patient');
    await go(page, '/services/pharmacy/prescriptions');
    await page.locator('article').filter({ hasText: /Bislol/ }).filter({ hasNotText: 'বাতিল' }).first().click();
    await settle(page);
    await page.getByRole('button', { name: 'ফার্মেসিতে পাঠান' }).last().click();
    await tap(page, 'দোকানে আছি — এই দোকানে পাঠান');
    const d = page.getByRole('dialog').last();
    await d.getByLabel('দোকানের নাম লিখুন').fill('লাইফ');
    await settle(page, 700);
    await J.shot('দোকানের নাম: লাইফ', '‘ডেমো লাইফ ফার্মেসি’ — খোলা, স্টকের হিসাব।');
    await d.locator('article, .card').filter({ hasText: 'ডেমো লাইফ ফার্মেসি' }).getByRole('button', { name: 'এই দোকানে দিন' }).first().click();
    await settle(page, 600);
    await J.shot('দোকানে পাঠানো (বা আগেই পাঠানো আছে)', '‘ডেমো লাইফ ফার্মেসি-এ পাঠানো হয়েছে’ (কেয়ারগিভার আগে পাঠিয়ে থাকলে ‘এই ফার্মেসিতে আগেই পাঠানো আছে’)।', { full: true });
    await page.keyboard.press('Escape');
  });

  await J.sc('R-23 R-26', 'ফার্মেসি: দোকানে আসা প্রেসক্রিপশনে ওষুধ দেওয়া', async () => {
    page = await J.as('pharmacy');
    await shop(page);
    await J.shot('আপনার কাছে পাঠানো', '‘ডেমো রোগী রহিম’ (দোকানে এসেছেন), নাসিমা বেগম ইত্যাদি — ‘খুলুন’, ‘তৈরি’, ‘পারব না’।', { full: true });
    const card = page.locator('div, article').filter({ hasText: 'ডেমো রোগী রহিম' }).filter({ has: page.getByRole('button', { name: 'তৈরি' }) }).last();
    if (await card.count()) {
      await card.getByRole('button', { name: 'তৈরি' }).click();
      await settle(page, 500);
      // ‘তৈরি’ চাপলে সারি ‘চলছে / তৈরি’ ট্যাবে যায়
      await (await orderRow(page, 'ডেমো রোগী রহিম', { button: 'খুলুন' })).getByRole('button', { name: 'খুলুন' }).first().click();
      await settle(page);
      await page.getByLabel('Comet: কয়টা দিলেন').fill('30').catch(() => {});
      await page.getByLabel('Bislol: কয়টা দিলেন').fill('30').catch(() => {});
      const none = page.locator('tr').filter({ hasText: 'Napa' }).getByText('নেই');
      if (await none.count()) await none.first().click();
      await J.shot('কয়টা দিলেন — Comet ৩০, Bislol ৩০, Napa নেই', 'Comet ৩০ (বাকি ৩০), Bislol ৩০, Napa ‘নেই’।', { full: true });
      await page.getByRole('button', { name: 'যা দিলাম লিখে রাখুন' }).click();
      await see(page, 'লেখা হয়েছে');
      await J.shot('লেখা হয়েছে', '‘লেখা হয়েছে’; Napa ‘চেয়েও পাননি’ তালিকায়।', { full: true });
      const later = page.getByRole('button', { name: 'এখন না' });
      if (await later.count()) await later.click();
    }
  });

  await J.sc('R-24', 'ফার্মেসি: অনলাইন অর্ডার দিতে না পারা', async () => {
    await shop(page, 'অনলাইন অর্ডার');
    const row = await orderRow(page, 'ডেমো রোগী আব্দুল করিম', { button: 'পারব না' });
    await row.getByRole('button', { name: 'পারব না' }).click();
    const d = page.getByRole('dialog').last();
    await J.shot('ওষুধ দিতে পারবেন না?', 'কারণ ‘ওষুধ নেই’।');
    await d.getByRole('button', { name: 'পারব না' }).click();
    await see(page, 'রোগীকে জানানো হয়েছে');
    await J.shot('অর্ডার ফেরত', '‘রোগীকে জানানো হয়েছে’; অর্ডার ‘আগের’-এ।', { full: true });
  });

  await J.sc('R-25', 'ফার্মেসি: বাতিল/শেষ প্রেসক্রিপশন আর বেশি দেওয়া আটকায়', async () => {
    await shop(page);
    await openCode(page, 'DEMXRXC444');
    await J.shot('বাতিল প্রেসক্রিপশন', '‘বাতিল করা প্রেসক্রিপশন — ওষুধ দেবেন না’; লেখার ঘর নেই।', { full: true });
    await openCode(page, 'DEMXRXA222');
    await J.shot('DEMXRXA222 — সব দেওয়া শেষ', 'সব ওষুধ ‘দেওয়া শেষ’, ‘আগে দেওয়া’-তে আড়াই মাস আগে ডেমো লাইফ।', { full: true });
    await openCode(page, 'DEMXRXK777');
    await page.getByLabel('Amdocal: কয়টা দিলেন').fill('50');
    await page.getByRole('button', { name: 'যা দিলাম লিখে রাখুন' }).click();
    await settle(page, 400);
    await J.shot('বেশি দেওয়া আটকায়', '‘Amdocal: এই প্রেসক্রিপশনে আর ৩০টা বাকি’।', { full: true });
  });

  await J.sc('R-27', 'প্যাথলজির অনুরোধের তালিকা', async () => {
    page = await J.as('lab');
    await labWork(page);
    // ২.৩x: ফিল্টারের বদলে ট্যাব — নতুন · নমুনা/চলছে · রিপোর্ট দেওয়া · শেষ (বাতিলও ‘শেষ’-এ)
    for (const f of ['নতুন', 'নমুনা', 'রিপোর্ট দেওয়া', 'শেষ']) {
      await page.getByRole('tab', { name: new RegExp(`^${f}`) }).first().click();
      await settle(page);
      await J.shot(`ট্যাব: ${f}`, f === 'শেষ' ? 'বাতিল: নাসিমা বেগম (USG)।' : f === 'নতুন' ? 'সালমা TSH, ফাতেমা (বাসা থেকে)।' : f === 'নমুনা' ? 'রহিম CBC (নমুনা নেওয়া)।' : 'রিপোর্ট দেওয়া: রহিম, সালমা, করিম — ‘দেখুন’।', { full: true });
    }
    const row = await orderRow(page, 'ডেমো রোগী সালমা খাতুন', { button: 'নমুনা নিয়েছি' });
    await row.getByRole('button', { name: 'নমুনা নিয়েছি' }).click();
    await settle(page, 500);
    await J.shot('সালমার নমুনা নেওয়া', 'সারি এখন ‘রিপোর্ট দিন’।', { full: true });
  });

  await J.sc('R-29', 'কাগজ নিয়ে আসা রোগী (কোড দিয়ে খোলা)', async () => {
    await labWork(page);
    await openCode(page, 'DEMXRXK777');
    await J.shot('কোড দিয়ে খোলা — DEMXRXK777', '‘ডেমো রোগী আব্দুল করিম’-এর Lipid profile, ECG নতুন অনুরোধ (নমুনা নেওয়া)।', { full: true });
    await labWork(page);
    await openCode(page, 'DEMXRXC444');
    await J.shot('বাতিল প্রেসক্রিপশন', '‘এই প্রেসক্রিপশন বাতিল’।');
  });

  await J.sc('R-31', 'অপেক্ষার ডাক্তারকে যাচাই করে অনুমোদন (অ্যাডমিন)', async () => {
    const P = await J.ctxFor('desktop');
    J.cur = P;
    page = P.page;
    if (P.role !== 'owner') {
      if (P.role && P.role !== 'guest') await logout(page).catch(() => {});
      await adminLogin(page);
      P.role = 'owner';
    }
    await page.goto(`${BASE}/admin`);
    await settle(page);
    await page.getByText('ব্যাজ', { exact: true }).first().click();
    await settle(page);
    await J.shot('অ্যাডমিন: ব্যাজ — অপেক্ষায়', '‘ডা. ডেমো তানভীর রহমান’ (DEMO-A-24444), ‘ডা. ডেমো কামরুল ইসলাম’ (সংশোধন) …', { full: true });
    await page.locator('tr').filter({ hasText: 'তানভীর' }).getByText('যাচাই করে অনুমোদন').first().click();
    await settle(page);
    await J.shot('তানভীর — যাচাই', 'কাগজের ছবি ঐচ্ছিক; BMDC লিংক; ‘আরও …টা বিষয় বাকি’ — বোতাম বন্ধ।', { full: true });
    const boxes = page.locator('input[type=checkbox]:visible');
    for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check({ force: true }).catch(() => {});
    await page.getByRole('button', { name: 'অনুমোদন দিন' }).click();
    await settle(page, 600);
    await J.shot('তানভীর অনুমোদিত', '‘যাচাই করে অনুমোদন দেওয়া হয়েছে’।');
  });
}
