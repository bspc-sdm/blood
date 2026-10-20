// স্বাস্থ্য, প্রোফাইল, নোটিফিকেশন, সাহায্য, সেটিংস আর মালিকের প্যানেল (গাইড health.json H-01…H-36)
import { tap, see, go, settle, logout } from '../lib.mjs';
import { adminAct } from '../lib.mjs';
import { BASE } from '../stack.mjs';
import { adminLogin } from './00-seed.mjs';

export const meta = { no: 7, slug: 'health-profile', titleBn: 'স্বাস্থ্য আলোচনা, টিপস, প্রোফাইল, নোটিফিকেশন, সাহায্য' };

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
async function menu(page, name, tab) {
  await page.getByText(name, { exact: true }).first().click();
  await settle(page);
  if (tab) {
    await page.getByRole('button', { name: tab, exact: true }).or(page.getByRole('tab', { name: tab, exact: true })).first().click();
    await settle(page);
  }
}

export async function run(J) {
  let page;

  await J.sc('H-23', 'নোটিফিকেশনের তালিকা: পড়া আর না-পড়া', async () => {
    page = await J.as('patient');
    await go(page, '/');
    await J.shot('ঘণ্টায় না-পড়ার সংখ্যা', 'ঘণ্টায় সংখ্যা (অন্তত ২)।');
    await go(page, '/inbox');
    await J.shot('নোটিফিকেশনের তালিকা', 'না-পড়া মোটা: ‘আপনার প্রশ্নের উত্তর এসেছে’, ‘Lifolo টিমের উত্তর এসেছে’; নিচে পড়াগুলো সাধারণ অক্ষরে।', { full: true });
    const team = page.getByText('Lifolo টিমের উত্তর এসেছে').first();
    if (await team.count()) {
      await team.click();
      await settle(page);
      await J.shot('Lifolo টিমের উত্তর চাপলে', '‘সাহায্য ও আপিল’ খোলে।', { full: true });
    }
    await go(page, '/');
    await J.shot('তালিকা দেখার পর ঘণ্টা', 'ঘণ্টার সংখ্যা চলে যায়।');
  });

  await J.sc('H-01', 'লগইন ছাড়া প্রশ্ন-উত্তর দেখা', async () => {
    page = await J.as('guest');
    await go(page, '/health/discussion');
    await J.shot('অতিথি: স্বাস্থ্য আলোচনা', 'ডেমো প্রশ্ন: টাইপ-২ ডায়াবেটিস, বাচ্চার জ্বর, হাঁটুতে ব্যথা, ‘গ্যারান্টি সহকারে…’; প্রশ্নকারীর নাম নেই (‘নাম প্রকাশে অনিচ্ছুক’)।', { full: true });
    await page.locator('article, [role=button]').filter({ hasText: 'টাইপ-২ ডায়াবেটিস' }).first().click();
    await settle(page);
    await J.shot('ডায়াবেটিসের প্রশ্ন — ২টি উত্তর', '‘ডা. ডেমো হাসান’ (ডাক্তার) আর ‘ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল’ (স্বাস্থ্য বিশেষজ্ঞ)।', { full: true });
    await page.getByRole('button', { name: 'রিপোর্ট' }).last().click();
    await settle(page);
    await J.shot('অতিথি রিপোর্ট চাপলে', 'লগইন চায় (‘রিপোর্ট করতে লগইন করুন’)।');
    await page.keyboard.press('Escape');
  });

  await J.sc('H-03', 'প্রশ্ন করা, বদলানো', async () => {
    page = await J.as('patient');
    await go(page, '/health/ask');
    await page.getByRole('button', { name: 'পোস্ট করুন' }).click();
    await settle(page, 300);
    await J.shot('খালি প্রশ্ন — পোস্ট হয় না', 'খালি লিখে পোস্ট হয় না।');
    await page.getByRole('textbox').first().fill('ডেমো পরীক্ষা: গ্যাস্ট্রিকের ওষুধ কতদিন খাওয়া যায়?');
    await page.getByRole('button', { name: 'পোস্ট করুন' }).click();
    await settle(page, 600);
    await go(page, '/health/discussion');
    await J.shot('নতুন প্রশ্ন তালিকার ওপরে', 'নাম ‘ডেমো রোগী রহিম’, ‘এখনো উত্তর নেই’।', { full: true });
    await page.locator('article, [role=button]').filter({ hasText: 'গ্যাস্ট্রিকের ওষুধ' }).first().click();
    await settle(page);
    await page.getByRole('button', { name: 'বদলান' }).first().click();
    const d = page.getByRole('dialog').last();
    const t = d.getByRole('textbox').first();
    await t.fill('ডেমো পরীক্ষা: গ্যাস্ট্রিকের ওষুধ কতদিন খাওয়া যায়? (বদলানো)');
    await d.getByRole('button', { name: /সেভ/ }).click();
    await settle(page, 600);
    await J.shot('প্রশ্ন বদলানো — সম্পাদিত', 'নতুন লেখা, পাশে ‘সম্পাদিত’।', { full: true });
  });

  await J.sc('H-06', 'নার্স হিসেবে উত্তর দেওয়া', async () => {
    page = await J.as('homecare');
    await go(page, '/health/discussion');
    await page.locator('article, [role=button]').filter({ hasText: 'শাশুড়ি' }).first().click();
    await settle(page);
    await page.getByLabel('উত্তর লিখুন').fill('ডেমো উত্তর: প্রতি ২ ঘণ্টায় পাশ ফেরান, বিছানা শুকনো রাখুন।');
    await page.getByRole('button', { name: 'উত্তর দিন' }).click();
    await settle(page, 600);
    await J.shot('নার্সের উত্তর', 'উত্তরের পাশে ‘নার্স’ ব্যাজ আর ‘ডেমো নার্স নাসরিন’।', { full: true });
  });

  await J.sc('H-07', 'টিপস দেখা — সাধারণ ব্যবহারকারী টিপস দিতে পারেন না', async () => {
    page = await J.as('patient');
    await go(page, '/health/tips');
    await J.shot('টিপস (রোগী)', 'ডেঙ্গু সবার ওপরে, উচ্চ রক্তচাপ, শিশুর ঠান্ডা-কাশি …; ‘টিপস দিন’ বোতাম নেই।', { full: true });
    if (await page.getByRole('button', { name: 'টিপস দিন' }).or(page.getByRole('link', { name: 'টিপস দিন' })).count()) throw new Error('ব্যাজ ছাড়া রোগী ‘টিপস দিন’ দেখছেন');
    await page.locator('article, [role=button]').filter({ hasText: 'ডেঙ্গু' }).first().click();
    await settle(page);
    await J.shot('ডেঙ্গুর টিপস', 'পুরো টিপস।', { full: true });
  });

  await J.sc('H-08', 'ডাক্তার হিসেবে টিপস দেওয়া ও বদলানো', async () => {
    page = await J.as('doctor', 'phone');
    await go(page, '/health/post-tip');
    const inputs = page.getByRole('textbox');
    await inputs.nth(0).fill('ডেমো টিপস: হাত ধোয়া');
    await page.getByRole('button', { name: 'পোস্ট করুন' }).click();
    await settle(page, 300);
    await J.shot('শুধু শিরোনাম — আটকায়', '‘শিরোনাম আর টিপস দুটোই লিখুন’।');
    await inputs.nth(1).fill('খাবারের আগে আর টয়লেটের পরে সাবান দিয়ে ২০ সেকেন্ড হাত ধুয়ে নিন।');
    await page.getByRole('button', { name: 'পোস্ট করুন' }).click();
    await settle(page, 600);
    await go(page, '/health/tips');
    await J.shot('নতুন টিপস ওপরে — ডাক্তার ব্যাজ', 'নতুন টিপস তালিকার ওপরে, ‘ডাক্তার’ ব্যাজসহ।', { full: true });
  });

  await J.sc('H-19', 'রোগীর হয়ে কাজ (ওয়েব) — ফিতা আর বন্ধ করা', async () => {
    page = await J.as('caregiver', 'desktop');
    await go(page, '/care');
    const row = page.locator('div, li, article').filter({ hasText: 'ডেমো রোগী রহিম' }).filter({ has: page.getByRole('button', { name: 'ওঁর হয়ে খুলুন' }) }).last();
    await row.getByRole('button', { name: 'ওঁর হয়ে খুলুন' }).click();
    await settle(page);
    await J.shot('ফিতা — রহিমের প্রেসক্রিপশন', 'ওপরে ফিতা ‘ডেমো রোগী রহিম-এর হয়ে কাজ করছেন’; রহিমের প্রেসক্রিপশন।', { full: true });
    await page.getByRole('link', { name: 'স্বাস্থ্য' }).first().click();
    await settle(page);
    await J.shot('অন্য পাতাতেও ফিতা থাকে', 'স্বাস্থ্য আলোচনায় গেলেও ফিতা।');
    await page.getByRole('button', { name: 'বন্ধ করুন' }).first().click();
    await settle(page);
    await page.getByRole('link', { name: 'আমার প্রেসক্রিপশন' }).first().click().catch(() => {});
    await settle(page);
    await J.shot('ফিতা বন্ধ — রুমার নিজের', 'ফিতা নেই; ‘আমার প্রেসক্রিপশন’-এ রুমার নিজের (খালি) তালিকা।', { full: true });
  });

  await J.sc('H-22', 'পরিবারের সদস্য (নিজের অ্যাকাউন্ট নেই)', async () => {
    page = await J.as('patient');
    await go(page, '/care');
    await J.shot('কেয়ারগিভার ও পরিবার (রহিম)', 'পরিবার: ‘ডেমো মা রাবেয়া বেগম’ (মা · ৬৮ · ০১২০০০০০৪০৮), ‘ডেমো ছেলে তামিম’ (ছেলে · ৯)।', { full: true });
    await tap(page, 'পরিবারের সদস্য যোগ করুন');
    const d = page.getByRole('dialog').last();
    await d.getByLabel('নাম').fill('ডেমো বাবা জব্বার');
    await d.getByLabel('সম্পর্ক (যেমন: মা)').fill('বাবা');
    await d.getByLabel('বয়স').fill('70');
    await d.getByText('পুরুষ', { exact: true }).click();
    await d.getByLabel('মোবাইল নম্বর (ঐচ্ছিক)').fill('12345');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 300);
    await J.shot('ভুল নম্বর — আটকায়', '‘সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)’।');
    await d.getByLabel('মোবাইল নম্বর (ঐচ্ছিক)').fill('');
    await d.getByRole('button', { name: 'সেভ' }).click();
    await settle(page, 500);
    await J.shot('নতুন সদস্য যোগ হলো', '‘ডেমো বাবা জব্বার’ তালিকায়; ‘সেভ হয়েছে’।', { full: true });
    const r = page.locator('div, li').filter({ hasText: 'ডেমো বাবা জব্বার' }).filter({ has: page.getByRole('button', { name: 'বদলান' }) }).last();
    await r.getByRole('button', { name: 'বদলান' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: 'এই সদস্যকে সরান' }).click();
    const c = page.getByRole('dialog').last();
    const yes = c.getByRole('button', { name: /হ্যাঁ|সরান/ });
    if (await yes.count()) await yes.last().click({ timeout: 2000 }).catch(() => {});
    await settle(page, 500);
    await J.shot('সদস্য সরানো হলো', 'জব্বার তালিকা থেকে চলে যান।', { full: true });
  });

  await J.sc('H-25', 'নিজের আগের অনুরোধগুলো (সাহায্য ও আপিল)', async () => {
    await go(page, '/help');
    await J.shot('সাহায্য ও আপিল — আমার অনুরোধ', 'তিনটা: অ্যাপয়েন্টমেন্ট — ‘উত্তরের অপেক্ষায়’; রক্তের অনুরোধ — ‘উত্তর এসেছে’; অ্যাকাউন্ট — ‘বন্ধ’।', { full: true });
    const ans = page.locator('article, [role=button], li').filter({ hasText: 'উত্তর এসেছে' }).first();
    if (await ans.count()) {
      await ans.click();
      await settle(page);
      await J.shot('উত্তর এসেছে — Lifolo টিমের উত্তর', '‘Lifolo টিম’-এর উত্তর: ‘ডোনার "রক্ত দেব" চাপার পরই তাঁর নম্বর…’; ‘আরও লিখুন’।', { full: true });
    }
  });

  await J.sc('H-09', 'নিজে ব্যাজের আবেদন করা (ওয়েবে)', async () => {
    page = await J.as('donor');
    await go(page, '/health/badge');
    await page.getByRole('group', { name: 'পেশা' }).getByText('ডাক্তার', { exact: true }).click();
    await page.getByLabel('রেজিস্ট্রেশন / লাইসেন্স নম্বর').fill('ডেমো-A-777');
    await page.getByLabel('কর্মস্থল').fill('ডেমো হাসপাতাল');
    await J.shot('ব্যাজের আবেদন', 'পেশা ডাক্তার, রেজি. ডেমো-A-777, কর্মস্থল ডেমো হাসপাতাল (ছবি ঐচ্ছিক)।', { full: true });
    await page.getByRole('button', { name: 'আবেদন করুন' }).click();
    await settle(page, 600);
    await go(page, '/profile');
    await J.shot('প্রোফাইলে ‘যাচাই চলছে’', '‘পেশাদার ব্যাজ’-এর নিচে ‘যাচাই চলছে’।', { full: true });
  });

  await J.sc('H-26', 'মালিক ওয়েব প্যানেল থেকে উত্তর দেন', async () => {
    page = await owner(J);
    await menu(page, 'আপিল ও সমস্যা');
    await J.shot('অ্যাডমিন: আপিল ও সমস্যা', 'রহিম (অ্যাপয়েন্টমেন্ট, ১ দিন আগে), ‘ডেমো ফিটনেস ট্রেইনার মিঠু’ (ব্যাজ)।', { full: true });
    await page.locator('tr').filter({ hasText: 'ডেমো রোগী রহিম' }).first().click();
    await settle(page);
    const ta = page.locator('textarea').first();
    await ta.fill('ডেমো উত্তর: ডাক্তার ৪৮ ঘণ্টায় সাড়া না দিলে অনুরোধ নিজে থেকে শেষ হয়, অন্য ডাক্তারের কাছে নিতে পারেন।');
    await page.getByRole('button', { name: 'উত্তর পাঠান' }).click();
    await settle(page, 600);
    await J.shot('উত্তর পাঠানো হলো', '‘উত্তর পাঠানো হয়েছে’; অনুরোধ ‘উত্তর দেওয়া’ ট্যাবে।', { full: true });
  });

  await J.sc('H-10', 'পুষ্টিবিদের ব্যাজ কাগজ দেখে অনুমোদন', async () => {
    await menu(page, 'ব্যাজ');
    await J.shot('অ্যাডমিন: ব্যাজ — অপেক্ষায়', '‘ডেমো পুষ্টিবিদ ফারহানা’ (ডেমো-NUT-0042), ডেমো ডোনার করিম …', { full: true });
    await page.locator('tr').filter({ hasText: 'ফারহানা' }).getByText('যাচাই করে অনুমোদন').first().click();
    await settle(page);
    await J.shot('ফারহানা — যাচাই', 'সনদের ছবিতে ‘DEMO CERTIFICATE — … NOT REAL’; ‘আরও ৩টা বিষয় বাকি’।', { full: true });
    const boxes = page.locator('input[type=checkbox]:visible');
    for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check({ force: true }).catch(() => {});
    await page.getByRole('button', { name: 'অনুমোদন দিন' }).click();
    await settle(page, 600);
    await J.shot('ফারহানা অনুমোদিত', '‘যাচাই করে অনুমোদন দেওয়া হয়েছে’।');
  });

  await J.sc('H-11', 'আবেদন কারণসহ বাতিল — আবেদনকারী কারণ দেখেন', async () => {
    await menu(page, 'ব্যাজ');
    const row = page.locator('tr').filter({ hasText: 'ডেমো ডোনার করিম' }).first();
    await row.locator('input.reason, input[placeholder*="কারণ"]').first().fill('BMDC-তে নম্বর পাওয়া যায়নি');
    await adminAct(page, row.getByRole('button', { name: 'বাতিল' }).first());
    const c = page.locator('dialog, [role=dialog]');
    if (await c.count()) await c.last().getByRole('button', { name: /বাতিল|হ্যাঁ|নিশ্চিত/ }).last().click().catch(() => {});
    await settle(page, 600);
    await J.shot('করিমের আবেদন বাতিল', '‘আবেদন বাতিল করা হয়েছে’।', { full: true });
    const P = await J.ctxFor('phone');
    J.cur = P;
    const dp = await J.as('donor');
    await go(dp, '/profile');
    await J.shot('করিমের প্রোফাইলে কারণ', '‘পেশাদার ব্যাজ’-এর নিচে ‘অনুমোদন হয়নি। আবার আবেদন করুন।’ আর ‘কারণ: BMDC-তে নম্বর পাওয়া যায়নি’।', { full: true });
    page = await owner(J);
  });

  await J.sc('H-14', 'ওয়েব প্যানেলে রিপোর্ট ‘রেখে দিন’', async () => {
    await menu(page, 'রিপোর্ট');
    const row = page.locator('tr').filter({ hasText: 'জামাল' }).first();
    await J.shot('অ্যাডমিন: উত্তরের রিপোর্ট', '‘কী: উত্তর · ২টি রিপোর্ট’, ‘কার: ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল’।', { full: true });
    await adminAct(page, row.getByRole('button', { name: 'রেখে দিন' }).first());
    await settle(page, 600);
    await J.shot('রেখে দেওয়া হলো', '‘রেখে দেওয়া হয়েছে’; সারি চলে যায়।', { full: true });
  });

  await J.sc('H-29', 'পুরোনো ‘শেষ রক্তদানের তারিখ’ সংশোধনের অনুরোধ', async () => {
    await menu(page, 'তারিখ সংশোধন');
    await J.shot('অ্যাডমিন: তারিখ সংশোধন', 'মাসুদ — এখনকার তারিখ (৪০ দিন আগে), চাওয়া তারিখ (১৫০ দিন আগে), কারণ।', { full: true });
    const row = page.locator('tr').filter({ hasText: 'মাসুদ' }).first();
    await adminAct(page, row.getByRole('button', { name: 'অনুমোদন' }).first());
    await settle(page, 600);
    await J.shot('তারিখ সংশোধন হলো', '‘তারিখ সংশোধন করা হয়েছে’; সারি চলে যায়।', { full: true });
  });

  await J.sc('H-30', 'ড্যাশবোর্ড আর অপেক্ষার কাজ', async () => {
    await menu(page, 'ড্যাশবোর্ড');
    await J.shot('অ্যাডমিন: ড্যাশবোর্ড', '‘অপেক্ষায় যা আছে’ — ব্যাজ, আপিল, রিপোর্ট, তারিখ সংশোধন…; ‘এক নজরে’ সংখ্যা; বাঁ মেনুতে একই সংখ্যা।', { full: true });
  });

  await J.sc('H-31', 'ব্যবহারকারী খোঁজা আর তাঁর সব কাজ দেখা', async () => {
    await menu(page, 'ব্যবহারকারী');
    const s = page.locator('input[type=search], input').first();
    await s.fill('patient@lifolo-demo.test');
    const go2 = page.getByRole('button', { name: 'খুঁজুন' });
    if (await go2.count()) await go2.first().click();
    else await s.press('Enter');
    await settle(page);
    await page.locator('tr').filter({ hasText: 'ডেমো রোগী রহিম' }).first().click();
    await settle(page);
    await J.shot('ব্যবহারকারীর পাতা — রহিম', '‘অ্যাপে যা যা করেছেন’ — আলোচনা, নোটিফিকেশন, আপিল, ডাক্তার ও অ্যাপয়েন্টমেন্ট।', { full: true });
    await menu(page, 'কাজের ইতিহাস');
    await J.shot('কাজের ইতিহাস', 'আগের অ্যাডমিন কাজগুলো (ব্যাজ অনুমোদন/বাতিল, রিপোর্ট, আপিলের উত্তর, কার পাতা দেখেছেন) — ইমেইলসহ।', { full: true });
  });

  await J.sc('H-32', 'ভাষা বদল (বাংলা ↔ English)', async () => {
    const P = await J.ctxFor('phone');
    J.cur = P;
    page = await J.as('caregiver');
    await go(page, '/settings');
    await page.getByText('English', { exact: true }).first().click();
    await settle(page, 600);
    await J.shot('Settings in English', 'সব বোতাম ইংরেজি: ‘Home, Appointment, Health, Services’।', { full: true });
    await go(page, '/health/discussion');
    await J.shot('Health Discussion in English', 'বোতাম ইংরেজি; ডেমো প্রশ্ন/নাম বাংলাই।', { full: true });
    await go(page, '/settings');
    await page.getByText('বাংলা', { exact: true }).first().click();
    await settle(page, 600);
    await J.shot('আবার বাংলা', 'সব আগের মতো বাংলায়।');
  });

  await J.sc('H-33', 'থিম: লাইট, ডার্ক, ফোনের মতো', async () => {
    await page.getByText('ডার্ক', { exact: true }).first().click();
    await settle(page, 400);
    await J.shot('ডার্ক থিম', 'কালো পটভূমি, লেখা পড়া যায়।', { full: true });
    await go(page, '/');
    await J.shot('ডার্ক থিমে হোম', 'হোম পাতাও ডার্কে, লেখা পরিষ্কার।', { full: true });
    await go(page, '/settings');
    await page.getByText('ফোনের মতো', { exact: true }).first().click();
    await settle(page, 400);
  });

  await J.sc('H-34', 'প্রোফাইল এডিট আর এলাকা বদল', async () => {
    await go(page, '/profile/edit');
    const phone = page.getByLabel(/মোবাইল/).first();
    const old = await phone.inputValue();
    await phone.fill('12345');
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await settle(page, 300);
    await J.shot('ভুল নম্বর — সেভ হয় না', '‘সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)’।', { full: true });
    await phone.fill(old);
    await page.getByLabel(/নাম/).first().fill('ডেমো কেয়ারগিভার রুমা আক্তার');
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await settle(page, 600);
    await go(page, '/profile');
    await J.shot('নতুন নাম', 'প্রোফাইলে ‘ডেমো কেয়ারগিভার রুমা আক্তার’।', { full: true });
  });

  await J.sc('H-36', 'রোল বদল আর লগআউট', async () => {
    page = await J.as('patient');
    await go(page, '/profile');
    await tap(page, 'রোল বদলান', { exact: true });
    await J.shot('রোল বদলান — সব ডেমো রোল', 'জানালায় সব ডেমো রোল।');
    await page.keyboard.press('Escape');
    page = await J.as('doctor', 'phone');
    await go(page, '/profile');
    await J.shot('ডাক্তার হয়ে ঢোকা', 'প্রোফাইলে ডাক্তারের নাম; ‘পেশাদার ব্যাজ’-এ ‘ডাক্তার · তথ্য ঠিক করতে চাপুন’।', { full: true });
    page = await J.as('guest', 'phone');
    await go(page, '/health/discussion');
    await J.shot('লগআউটের পর অতিথি', 'অতিথি — প্রশ্ন দেখা যায়, নাম দেখায় না।', { full: true });
  });

  await J.sc('H-35', 'অ্যাকাউন্ট মোছা আর ৩০ দিনের মধ্যে ফেরত (কেয়ারগিভার)', async () => {
    page = await J.as('caregiver');
    await go(page, '/profile');
    await tap(page, 'অ্যাকাউন্ট মুছুন', { exact: true });
    const d = page.getByRole('dialog').last();
    await J.shot('অ্যাকাউন্ট মুছবেন?', '‘৩০ দিন পর আপনার অ্যাকাউন্ট চিরতরে মুছে যাবে… ৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে…’।');
    await d.getByRole('button', { name: 'অ্যাকাউন্ট মুছুন' }).click();
    await settle(page, 800);
    await J.shot('মোছার অনুরোধ — লগআউট', '‘৩০ দিন পর আপনার অ্যাকাউন্ট মুছে যাবে…’; অতিথি অবস্থা।', { full: true });
    const P = Object.values(J.pages).find((x) => x.page === page);
    P.role = 'guest';
    page = await J.as('caregiver');
    await J.shot('আবার ঢুকলে মোছা বাতিল', '‘অ্যাকাউন্ট মোছা বাতিল হয়েছে।’; সব আগের মতো।');
  });
}
