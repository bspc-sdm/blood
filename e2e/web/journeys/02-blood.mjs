// রক্তদান (গাইড blood.json B-01…B-31): অতিথি, ডোনার, রোগীর রিকোয়েস্ট, রাজি → নিশ্চিত → রক্ত পেয়েছি, ব্লাড ব্যাংক, অ্যাডমিন
import { tap, see, go, settle, logout } from '../lib.mjs';
import { adminAct } from '../lib.mjs';
import { BASE } from '../stack.mjs';
import { adminLogin } from './00-seed.mjs';

export const meta = { no: 2, slug: 'blood', titleBn: 'রক্তদান: রিকোয়েস্ট, ডোনার, ব্লাড ব্যাংক' };

/** অতিথির এলাকা: জেলা গাজীপুর */
export async function pickDistrict(page, name = 'গাজীপুর', J = null) {
  const dlg = page.getByRole('dialog').last();
  await dlg.waitFor();
  const search = dlg.getByRole('textbox').first();
  if (await search.count()) await search.fill(name);
  await settle(page, 200);
  if (J) await J.shot('এলাকা খোঁজা — ‘গাজীপুর’ লিখলে জেলা আসে', 'জেলা খোঁজার ঘরে ‘গাজীপুর’ লিখলে তালিকায় গাজীপুর।', { status: 'fixed', noteBn: 'আগে কিবোর্ডে ‘গাজীপুর’ লিখলে (য় দুই রকম ইউনিকোডে লেখা যায়) জেলা খুঁজে পেত না — তালিকা খালি থাকত। এলাকা, হাসপাতাল আর উপজেলার খোঁজে এখন দুই রূপই মেলে।' });
  // খোঁজার ফলের প্রথম সারি (তালিকার নাম অন্য ইউনিকোড রূপে থাকতে পারে, তাই লেখা মিলিয়ে নয়)
  await dlg.locator('[style*="overflow-y"] > *').first().click();
  await settle(page);
  // উপজেলা জিজ্ঞেস করলে "সব"/জেলা পর্যায়ে থাকা
  const d2 = page.getByRole('dialog');
  if (await d2.count()) {
    const all = d2.last().getByText(/পুরো জেলা|সব উপজেলা|গাজীপুর জেলা/).first();
    if (await all.count()) await all.click().catch(() => {});
    await settle(page);
  }
}

/** হোমের তালিকায় একটা রিকোয়েস্ট খোলা */
async function openReq(page, hospital) {
  await go(page, '/');
  await page.locator('article').filter({ hasText: hospital }).first().click();
  await settle(page);
}

async function donate(page) {
  await page.getByRole('button', { name: 'রক্ত দেব' }).first().click();
  const dlg = page.getByRole('dialog').last();
  await dlg.waitFor();
  const boxes = dlg.getByRole('checkbox');
  for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check({ force: true });
  return dlg;
}

/** "আমার রিকোয়েস্ট" কার্ড (হোমের ওপরে) */
const myCard = (page) => page.locator('section, .card').filter({ hasText: 'আমার রিকোয়েস্ট' }).filter({ hasText: 'ডেমো জয়দেবপুর জেনারেল হাসপাতাল' }).first();

export async function run(J) {
  let page;

  await J.sc('B-01', 'লগইন ছাড়া আশেপাশের রক্তের রিকোয়েস্ট দেখা', async () => {
    page = await J.as('guest');
    await go(page, '/');
    await J.shot('অতিথি: প্রথম পাতা', 'এলাকা বেছে নিতে বলে (বা আশেপাশের রিকোয়েস্ট)।');
    const choose = page.getByRole('button', { name: /এলাকা বেছে নিন|এলাকা বদলান/ }).first();
    if (await choose.count()) {
      await choose.click();
      await pickDistrict(page, 'গাজীপুর', J);
    }
    await J.shot('অতিথি: আশেপাশের রিকোয়েস্ট (গাজীপুর)', 'সবার ওপরে A+ ‘ডেমো শিববাড়ি জেনারেল হাসপাতাল’ (লাল ‘জরুরি’), তারপর জয়দেবপুর বাজার (যাতায়াত খরচ), ছায়াবীথি (১ জন রাজি), থ্যালাসেমিয়া সেন্টার, শিববাড়ি (O-, A+), রেলগেট (ডোনার পাওয়া গেছে); শ্রীপুর নেই।', { full: true });
    if (await page.getByText('ডেমো শ্রীপুর উপজেলা স্বাস্থ্য কমপ্লেক্স').count()) throw new Error('১০ কিমির বাইরের শ্রীপুর তালিকায় দেখা যাচ্ছে');
  });

  await J.sc('B-02', 'অতিথি রক্তপ্রার্থীর নাম-ফোন দেখেন না, সাড়া দিতে লগইন লাগে', async () => {
    await openReq(page, 'ডেমো শিববাড়ি জেনারেল হাসপাতাল');
    await J.shot('অতিথি: রিকোয়েস্টের পাতা', 'হাসপাতাল, এলাকা, কবে, ব্যাগ — রক্তপ্রার্থীর নাম বা ফোন নেই।', { full: true });
    const b = page.getByRole('button', { name: /রক্ত দেব|রক্ত দিতে চাই|লগইন/ }).first();
    await b.click();
    await settle(page);
    await J.shot('সাড়া দিতে লগইন চায়', '‘সাড়া দিতে লগইন করুন’ ধরনের লেখা আর লগইনের ঘর।');
    await page.keyboard.press('Escape');
  });

  await J.sc('B-03', 'ব্লাড ব্যাংকের তালিকা আর ডেমো আহ্বান লুকানো (অতিথি)', async () => {
    await go(page, '/');
    await J.shot('অতিথি: হোমে ডেমো জরুরি আহ্বান নেই', '‘ডেমো তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল (জরুরি বিভাগ)’-এর ডেমো আহ্বান অতিথি দেখেন না।');
    if (await page.getByText('ডেমো আহ্বান (আসল নয়)').count()) throw new Error('অতিথি ডেমো জরুরি আহ্বান দেখছেন');
    await go(page, '/services');
    await tap(page, 'ব্লাড ব্যাংক', { role: 'tab' }).catch(() => tap(page, 'ব্লাড ব্যাংক'));
    await J.shot('সেবা › ব্লাড ব্যাংক', '৩টা ডেমো: জীবনধারা (২৪ ঘণ্টা), সদর হাসপাতাল (সরকারি, ৮–৮, শুক্র বন্ধ), আলোর পথ (স্বেচ্ছাসেবী); ‘রক্তবন্ধু’ নেই।', { full: true });
    await tap(page, 'ফিল্টার');
    const sw = page.getByRole('dialog').last().getByText(/২৪ ঘণ্টা/).first();
    await sw.click();
    const show = page.getByRole('dialog').last().getByRole('button', { name: /দেখান|ঠিক আছে|প্রয়োগ/ });
    if (await show.count()) await show.first().click();
    else await page.keyboard.press('Escape');
    await settle(page);
    await J.shot('শুধু ২৪ ঘণ্টা খোলা', 'শুধু ‘ডেমো জীবনধারা ব্লাড ব্যাংক’।');
  });

  await J.sc('B-04', 'ডোনারের প্রোফাইল: যোগ্যতা আর শেষ রক্তদান', async () => {
    page = await J.as('donor');
    await go(page, '/profile');
    await J.shot('ডোনার করিমের প্রোফাইল', 'O+, সবুজ ‘এখন রক্ত দিতে পারবেন’; শেষ রক্তদান ~১৫০ দিন আগে; ৩২ বছর · ৬৮ কেজি; ফোন ০১২০০০০১১০২; ‘রোল বদলান’।', { full: true });
  });

  await J.sc('B-05', 'হোমে নিজের ‘প্রসেসিং’ আর মানানসই রিকোয়েস্ট', async () => {
    await go(page, '/');
    await J.shot('ডোনারের হোম', 'ওপরে কাউন্টডাউনসহ ‘ডেমো রেলগেট ক্লিনিক, জয়দেবপুর’ (প্রসেসিং); তালিকায় A+, B+, O+, AB+ — O- নেই; জরুরি আগে।', { full: true });
  });

  await J.sc('B-06', 'রাজি হওয়া রিকোয়েস্টে রক্তপ্রার্থীর নম্বর দেখা যায়', async () => {
    await go(page, '/');
    await page.locator('article, .card').filter({ hasText: 'ডেমো রেলগেট ক্লিনিক, জয়দেবপুর' }).first().click();
    await settle(page);
    await J.shot('নিশ্চিত রিকোয়েস্ট — রক্তপ্রার্থীর নম্বর', '‘রক্তপ্রার্থী আপনাকে নিশ্চিত করেছেন।’; ‘ডেমো রক্তপ্রার্থী রেহানা’ ০১২০০০০১২১৯; ‘রক্ত দিয়েছি’ আর ‘আর পারছি না, বাতিল করুন’।', { full: true });
  });

  await J.sc('B-07', 'রাজি হওয়ার আগে নম্বর গোপন, রাজি হলে দেখা যায়, বাতিল করলে আবার গোপন', async () => {
    await openReq(page, 'ডেমো শিববাড়ি জেনারেল হাসপাতাল');
    await J.shot('রাজি হওয়ার আগে', 'শুধু নাম ‘ডেমো রক্তপ্রার্থী সেলিনা’, ফোন নেই; ‘রক্তপ্রার্থী আপনার নাম ও নম্বর দেখতে পাবেন।’', { full: true });
    const dlg = await donate(page);
    await J.shot('রক্ত দেওয়ার আগে — ৫টা টিক', 'পাঁচটা ঘরে টিক, ‘রক্ত দেব’ চালু।');
    await dlg.getByRole('button', { name: 'রক্ত দেব' }).click();
    await see(page, 'রক্তপ্রার্থী আপনাকে নিশ্চিত করার অপেক্ষায়');
    await J.shot('রাজি হলাম — নম্বর দেখা যায়', '‘নিশ্চিত করার অপেক্ষায়’; যোগাযোগে ০১২০০০০১২১৩।', { full: true });
    await tap(page, 'আর পারছি না, বাতিল করুন');
    const c = page.getByRole('dialog');
    if (await c.count()) await c.last().getByRole('button', { name: /বাতিল করুন|হ্যাঁ/ }).last().click().catch(() => {});
    await see(page, 'বাতিল করা হয়েছে');
    await J.shot('বাতিলের পর আবার গোপন', '‘বাতিল করা হয়েছে। রক্তপ্রার্থী এখন আর আপনার নম্বর দেখবেন না।’; ‘আবার রক্ত দিতে চাই’।');
  });

  await J.sc('B-08', 'যে গ্রুপের রিকোয়েস্ট দেখাবে / কতদূর যেতে পারবেন (সেটিংস)', async () => {
    await go(page, '/settings');
    await J.shot('ডোনারের অ্যাপ সেটিংস', 'নোটিফিকেশন, ‘যে গ্রুপের রিকোয়েস্ট দেখাবে’ (O+, A+, B+, AB+), ‘রক্ত দিতে কতদূর যেতে পারবেন?’।', { full: true });
    const grp = page.locator('.field, section, div').filter({ hasText: 'যে গ্রুপের রিকোয়েস্ট দেখাবে' }).last();
    await grp.getByText('O-', { exact: true }).click();
    await settle(page, 500);
    await go(page, '/');
    await J.shot('O- যোগ করার পর হোম', '‘ডেমো জয়দেবপুর জেনারেল হাসপাতাল’-এর O- রিকোয়েস্ট (নুরুল) তালিকায়।', { full: true });
    await page.locator('article').filter({ hasText: 'O-' }).filter({ hasText: 'ডেমো জয়দেবপুর জেনারেল হাসপাতাল' }).first().click();
    await settle(page);
    await J.shot('O- রিকোয়েস্ট — রক্ত দেওয়া যায় না', '‘আপনার গ্রুপ (O+) থেকে O- রোগীকে রক্ত দেওয়া যায় না…’; ‘রক্ত দেব’ নেই।');
    await go(page, '/settings');
    await page.locator('.field, section, div').filter({ hasText: 'যে গ্রুপের রিকোয়েস্ট দেখাবে' }).last().getByText('O-', { exact: true }).click();
    const dist = page.locator('.field, section, div').filter({ hasText: 'রক্ত দিতে কতদূর যেতে পারবেন?' }).last();
    await dist.getByText(/^২৫/).first().click();
    await settle(page, 500);
    await go(page, '/');
    await J.shot('২৫ কিমিতে শ্রীপুর দেখা যায়', '‘ডেমো শ্রীপুর উপজেলা স্বাস্থ্য কমপ্লেক্স’ (B+, ~১৬ কিমি)।', { full: true });
    await go(page, '/settings');
    await page.locator('.field, section, div').filter({ hasText: 'রক্ত দিতে কতদূর যেতে পারবেন?' }).last().getByText(/^১০/).first().click();
    await settle(page, 400);
  });

  await J.sc('B-09', 'নোটিফিকেশনের সেটিংস', async () => {
    await go(page, '/settings');
    const per = page.locator('.field, section, div').filter({ hasText: 'দিনে সর্বোচ্চ' }).last();
    await per.getByText('৫', { exact: true }).first().click();
    const pg = page.locator('.field, section, div').filter({ hasText: 'রোগীর যে গ্রুপগুলোর জন্য' }).last();
    await pg.getByText('AB+', { exact: true }).first().click();
    const fare = page.locator('.field, section, div').filter({ hasText: 'যাতায়াত খরচ' }).last();
    await fare.getByText(/৫ কিমির বেশি/).first().click();
    await settle(page, 600);
    await go(page, '/');
    await go(page, '/settings');
    await J.shot('আবার খুললে বদলগুলো থেকে যায়', 'দিনে সর্বোচ্চ ৫, AB+ বাদ, ‘৫ কিমির বেশি হলে’ খরচ; গ্রুপে শুধু O+, A+, B+, AB+।', { full: true });
    // আগের মতো
    await page.locator('.field, section, div').filter({ hasText: 'দিনে সর্বোচ্চ' }).last().getByText('৩', { exact: true }).first().click();
    await page.locator('.field, section, div').filter({ hasText: 'রোগীর যে গ্রুপগুলোর জন্য' }).last().getByText('AB+', { exact: true }).first().click();
    await page.locator('.field, section, div').filter({ hasText: 'যাতায়াত খরচ' }).last().getByText('লাগবে না', { exact: true }).first().click();
    await settle(page, 400);
  });

  await J.sc('B-10', 'জরুরি আহ্বান: ‘আসছি’ / ‘আসতে পারছি না’, শেষ হওয়া আহ্বানে ‘রক্ত দিয়েছেন?’', async () => {
    await go(page, '/');
    const card = page.locator('section, .card, article').filter({ hasText: 'জরুরি আহ্বান · Lifolo টিম' }).first();
    await J.shot('জরুরি আহ্বানের কার্ড', 'O+ ‘১/৪ আসছেন’, ‘আসছি’ বোতাম।');
    await card.getByRole('button', { name: 'আসছি' }).click();
    await settle(page, 500);
    await J.shot('আসছি চাপার পর', '‘আপনি আসছেন — ধন্যবাদ! সাথে পরিচয়পত্র আনুন।’, O+ ‘২/৪ আসছেন’।');
    await page.getByRole('button', { name: 'আসতে পারছি না' }).first().click();
    await settle(page, 500);
    await J.shot('আসতে পারছি না', 'আবার O+ ‘১/৪ আসছেন’ আর ‘আসছি’।');
    const no = page.getByRole('button', { name: 'না, দিইনি' });
    if (await no.count()) {
      await J.shot('শেষ হওয়া আহ্বান — রক্ত দিয়েছেন?', '‘ডেমো সদর হাসপাতাল, গাজীপুর’ — ‘হ্যাঁ, দিয়েছি’ / ‘না, দিইনি’।');
      await no.first().click();
      await see(page, 'জানানোর জন্য ধন্যবাদ');
      await J.shot('না, দিইনি — ধন্যবাদ', '‘জানানোর জন্য ধন্যবাদ।’; প্রশ্ন চলে যায়।');
    } else {
      await J.shot('শেষ হওয়া আহ্বানের প্রশ্ন দেখা যায়নি', '‘রক্ত দিয়েছেন?’ প্রশ্ন থাকার কথা', { status: 'bug', noteBn: 'ডোনারের হোমে শেষ হওয়া ডেমো আহ্বানের ‘রক্ত দিয়েছেন?’ প্রশ্ন পাওয়া যায়নি।' });
    }
  });

  await J.sc('B-11', 'রোগীর হোম: ‘আমার রিকোয়েস্ট’ আর কারা রাজি', async () => {
    page = await J.as('patient');
    await go(page, '/');
    await J.shot('রোগীর হোম — আমার রিকোয়েস্ট', 'ওপরে ‘রক্ত দিতে চাই’; ‘আমার রিকোয়েস্ট’: A+ শিববাড়ি, ‘৭ জন ডোনারকে জানানো হয়েছে’, ‘২ জন রাজি · ১ জন নিশ্চিত’; সাকিব (নিশ্চিত), নাদিয়া (নিশ্চিত করুন/বাদ দিন)।', { full: true });
  });

  await J.sc('B-12', 'আমার রিকোয়েস্টের ইতিহাস আর নোটিফিকেশন', async () => {
    await go(page, '/my-requests');
    await J.shot('আমার রিকোয়েস্ট — ৩টা', 'খোলা ‘ডেমো জয়দেবপুর জেনারেল হাসপাতাল’; ‘বন্ধ’ তাজউদ্দীন মেডিকেল গেট (৪০ দিন আগে) আর ছায়াবীথি (১২ দিন আগে)।', { full: true });
    await page.locator('article, [role=button]').filter({ hasText: 'ডেমো শিববাড়ি জেনারেল হাসপাতাল' }).first().click();
    await settle(page);
    await J.shot('বন্ধ পুরোনো রিকোয়েস্ট', 'ফারহানা আর জাহিদ — ‘রক্ত দিয়েছেন’; ‘নিশ্চিত করুন’/‘বাদ দিন’ নেই।', { full: true });
    await go(page, '/inbox');
    await J.shot('রোগীর ঘণ্টা', 'দুটো ‘একজন ডোনার রাজি হয়েছেন’ (সাকিব, নাদিয়া)।', { full: true });
  });

  await J.sc('B-13', 'রিকোয়েস্ট এডিট (গ্রুপ আর এলাকা বদলানো যায় না)', async () => {
    await go(page, '/my-requests');
    await page.locator('article, [role=button]').filter({ hasText: 'ডেমো জয়দেবপুর জেনারেল হাসপাতাল' }).first().click();
    await settle(page);
    await page.getByRole('button', { name: 'এডিট' }).first().click();
    await settle(page);
    await J.shot('রিকোয়েস্ট এডিট', '‘গ্রুপ আর এলাকা বদলানো যায় না’।', { full: true });
    await page.getByLabel('অতিরিক্ত তথ্য (ঐচ্ছিক)').fill('ডেমো: সকাল ১০টার মধ্যে আসুন');
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await see(page, 'সেভ হয়েছে');
    await J.shot('এডিট সেভ হলো', '‘সেভ হয়েছে।’; নতুন অতিরিক্ত তথ্য দেখা যায়।', { full: true });
  });

  await J.sc('B-14', 'নতুন রিকোয়েস্ট দেওয়া, একসাথে ২টার সীমা, রিকোয়েস্ট মুছে ফেলা', async () => {
    await go(page, '/request/new');
    await settle(page);
    await page.getByText('O+', { exact: true }).first().click();
    await page.getByLabel(/হাসপাতাল/).first().fill('ডেমো');
    await settle(page, 600);
    await J.shot('হাসপাতালের পরামর্শ', '‘ডেমো’ লিখলে ‘ডেমো জয়দেবপুর জেনারেল হাসপাতাল’ পরামর্শ।');
    await page.getByText('ডেমো জয়দেবপুর জেনারেল হাসপাতাল').first().click();
    const area = page.getByRole('button', { name: /এলাকা বেছে নিন/ });
    if (await area.count()) { await area.first().click(); await pickDistrict(page); }
    await page.getByLabel('তারিখ').fill((await page.evaluate(() => new Date(Date.now() + 864e5 + 6 * 3600e3).toISOString().slice(0, 10))));
    await page.getByLabel('সময়', { exact: true }).fill('10:00');
    await page.getByLabel('অতিরিক্ত তথ্য (ঐচ্ছিক)').fill('ডেমো — আসল নয়');
    await J.shot('নতুন রিকোয়েস্টের ঘর', 'O+, ১ ব্যাগ, ডেমো জয়দেবপুর জেনারেল হাসপাতাল, গাজীপুর, কাল সকাল ১০টা, ‘ডেমো — আসল নয়’ (সময় না দিলে ‘সময় বেছে নিন’)।', { full: true });
    await page.getByRole('button', { name: 'রিকোয়েস্ট দিন' }).last().click();
    await page.waitForURL(/my-requests\//, { timeout: 15000 });
    await settle(page);
    await go(page, '/my-requests');
    await J.shot('দুটো খোলা রিকোয়েস্ট — সীমা', '‘একসাথে সর্বোচ্চ ২টা খোলা রিকোয়েস্ট রাখা যায়।’; ‘রিকোয়েস্ট’ (+) নেই।', { full: true });
    await page.locator('article, [role=button]').filter({ hasText: 'O+' }).filter({ hasText: 'ডেমো জয়দেবপুর জেনারেল হাসপাতাল' }).first().click();
    await settle(page);
    await page.getByRole('button', { name: 'মুছে ফেলুন' }).first().click();
    const d = page.getByRole('dialog').last();
    await J.shot('মুছে ফেলবেন?', '‘রিকোয়েস্টটা মুছে ফেলবেন?’');
    await d.getByRole('button', { name: 'মুছে ফেলুন' }).click();
    await settle(page);
    await go(page, '/my-requests');
    await J.shot('মোছার পর একটা খোলা', 'আবার একটা খোলা রিকোয়েস্ট।', { full: true });
  });

  await J.sc('B-15', 'ডোনার রোগীর রিকোয়েস্টে রাজি হন, রোগী ডোনারের নাম-নম্বর দেখেন', async () => {
    page = await J.as('donor');
    await openReq(page, 'ডেমো জয়দেবপুর জেনারেল হাসপাতাল');
    const dlg = await donate(page);
    await dlg.getByRole('button', { name: 'রক্ত দেব' }).click();
    await see(page, 'রক্তপ্রার্থী আপনাকে নিশ্চিত করার অপেক্ষায়');
    await J.shot('ডোনার রাজি — রোগীর নম্বর', '‘নিশ্চিত করার অপেক্ষায়।’; যোগাযোগে ‘ডেমো রোগী রহিম’ ০১২০০০০১১০১।', { full: true });
    page = await J.as('patient');
    await go(page, '/');
    await J.shot('রোগী: ৩ জন রাজি', '‘৩ জন রাজি · ১ জন নিশ্চিত’; নতুন ‘ডেমো ডোনার করিম’ (O+, ০১২০০০০১১০২) — ‘নিশ্চিত করুন’, ‘বাদ দিন’।', { full: true });
    await go(page, '/inbox');
    await J.shot('রোগীর ঘণ্টায় করিম রাজি', '‘ডেমো ডোনার করিম (O+) রক্ত দিতে রাজি…’।');
  });

  await J.sc('B-16', 'ডোনার বাদ দেওয়া আর ফিরিয়ে আনা', async () => {
    await go(page, '/');
    const row = myCard(page).locator('div, li').filter({ hasText: 'ডেমো ডোনার নাদিয়া' }).filter({ has: page.getByRole('button', { name: 'বাদ দিন' }) }).last();
    await row.getByRole('button', { name: 'বাদ দিন' }).click();
    const d = page.getByRole('dialog').last();
    await J.shot('নাদিয়াকে বাদ দেবেন?', '‘ডেমো ডোনার নাদিয়া-কে বাদ দেবেন?’');
    await d.getByRole('button', { name: 'বাদ দিন' }).click();
    await settle(page, 500);
    await J.shot('বাদ দেওয়া হলো — ফিরিয়ে আনুন', '‘…-কে বাদ দেওয়া হয়েছে’ আর ‘ফিরিয়ে আনুন’।');
    await tap(page, 'ফিরিয়ে আনুন');
    await settle(page, 500);
    await J.shot('নাদিয়া ফিরে এলেন', 'নাদিয়া আবার রাজি তালিকায়।', { full: true });
  });

  await J.sc('B-17', 'রোগী ডোনারকে নিশ্চিত করেন — ব্যাগ পূরণ', async () => {
    await go(page, '/');
    const row = myCard(page).locator('div, li').filter({ hasText: 'ডেমো ডোনার করিম' }).filter({ has: page.getByRole('button', { name: 'নিশ্চিত করুন' }) }).last();
    await row.getByRole('button', { name: 'নিশ্চিত করুন' }).click();
    await settle(page, 600);
    await J.shot('করিম নিশ্চিত — দুটো ব্যাগ পূরণ', '‘৩ জন রাজি · ২ জন নিশ্চিত’, দুটো ব্যাগ লাল; নাদিয়ার ‘নিশ্চিত করুন’ নেই।', { full: true });
    page = await J.as('donor');
    await openReq(page, 'ডেমো জয়দেবপুর জেনারেল হাসপাতাল');
    await J.shot('ডোনার: নিশ্চিত করা হয়েছে', '‘রক্তপ্রার্থী আপনাকে নিশ্চিত করেছেন। সময়মতো হাসপাতালে যান।’');
    await go(page, '/inbox');
    await J.shot('ডোনারের ঘণ্টা', '‘আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন’।');
  });

  await J.sc('B-18', '‘রক্ত পেয়েছি’ — রিকোয়েস্ট বন্ধ, ডোনারের শেষ রক্তদানের তারিখ আজ', async () => {
    page = await J.as('patient');
    await go(page, '/');
    await myCard(page).getByRole('button', { name: 'রক্ত পেয়েছি' }).click();
    const d = page.getByRole('dialog').last();
    await d.waitFor();
    await d.getByText('২', { exact: true }).first().click().catch(() => {});
    for (const n of ['ডেমো ডোনার সাকিব', 'ডেমো ডোনার করিম']) {
      const c = d.getByRole('checkbox', { name: new RegExp(n) });
      if (await c.count()) await c.check({ force: true });
      else await d.getByText(n).click();
    }
    await J.shot('রিকোয়েস্ট বন্ধ করবেন?', '‘সব ব্যাগ পেয়েছেন — রিকোয়েস্ট বন্ধ হবে…’, ‘ভুল হলে ১ ঘণ্টার মধ্যে আবার খুলতে পারবেন।’; সাকিব আর করিম টিক।');
    await d.getByRole('button', { name: 'বন্ধ করুন' }).click();
    await settle(page, 600);
    await J.shot('রিকোয়েস্ট বন্ধ হলো', '‘…রিকোয়েস্ট বন্ধ হয়েছে’ আর ‘আবার খুলুন’।', { full: true });
    page = await J.as('donor');
    await go(page, '/profile');
    await J.shot('ডোনার: এখন রক্ত দিতে পারবেন না', '‘এখন রক্ত দিতে পারবেন না’ — ১২০ দিন পরের তারিখ; শেষ রক্তদান আজ।', { full: true });
    await go(page, '/');
    await J.shot('ডোনারের হোম — রেলগেট কার্ড চলে গেছে', 'ওপরের ‘ডেমো রেলগেট ক্লিনিক, জয়দেবপুর’ কার্ড নেই।', { full: true });
    await go(page, '/inbox');
    await J.shot('ডোনারের ঘণ্টা — ধন্যবাদ', '‘রক্ত দেওয়ার জন্য ধন্যবাদ!’');
  });

  await J.sc('B-19', 'সদ্য রক্ত দেওয়া ডোনার আর সাড়া দিতে পারেন না', async () => {
    await openReq(page, 'ডেমো ছায়াবীথি মা ও শিশু ক্লিনিক');
    await J.shot('রক্ত দেব-এর বদলে পরের তারিখ', '‘<তারিখ> থেকে আবার রক্ত দিতে পারবেন’।');
    await go(page, '/');
    await J.shot('জরুরি আহ্বানে আসছি নেই', '‘এখন আপনি রক্ত দিতে পারবেন না (শেষ রক্তদান, বয়স বা ওজন)।’');
  });

  await J.sc('B-20', 'রোগী রক্তদাতা হন (‘রক্ত দিতে চাই’)', async () => {
    page = await J.as('patient');
    await go(page, '/');
    await page.getByRole('button', { name: 'রক্ত দিতে চাই' }).first().click();
    const d = page.getByRole('dialog').last();
    await d.waitFor();
    await d.getByText('B+', { exact: true }).first().click();
    const dob = d.getByLabel(/জন্ম/).first();
    if (await dob.count()) await dob.fill('1992-01-10');
    await d.getByLabel(/ওজন/).first().fill('65');
    const never = d.getByText('কখনো রক্ত দিইনি');
    if (await never.count()) await never.first().click();
    await J.shot('রক্তদাতার তথ্য', 'B+, জন্ম ১০ জানুয়ারি ১৯৯২, ওজন ৬৫, ‘কখনো রক্ত দিইনি’; ওপরে ‘রক্তদাতা হতে লগইন করুন’ লেখা নেই (লগইন করাই আছে)।', { status: 'fixed', noteBn: 'আগে লগইন করা রোগী ‘রক্ত দিতে চাই’ চাপলে রক্তদাতার তথ্যের ঘরের ওপরে ভুল করে ‘রক্তদাতা হতে লগইন করুন’ লেখা থাকত। এখন এই লেখা শুধু লগইনের ধাপে।' });
    await d.getByRole('button', { name: /সেভ করুন|এগিয়ে যান/ }).click();
    await settle(page, 600);
    await go(page, '/profile');
    await J.shot('প্রোফাইলে B+ — এখন রক্ত দিতে পারবেন', 'B+ আর ‘এখন রক্ত দিতে পারবেন’; ‘শেষ রক্তদান’ ‘কখনো দেননি’।', { full: true });
    await go(page, '/');
    await J.shot('হোমে শুধু B+ আর AB+ রিকোয়েস্ট', '‘রক্ত দিতে চাই’ কার্ড নেই; জয়দেবপুর বাজার (B+), থ্যালাসেমিয়া সেন্টার (AB+)।', { full: true });
  });

  await J.sc('B-21', 'সম্প্রতি রক্ত দেওয়া ডোনার: পরের তারিখ দেখায়', async () => {
    page = await J.as('caregiver');
    await go(page, '/profile');
    await J.shot('রুমার প্রোফাইল', 'B+, ‘এখন রক্ত দিতে পারবেন না’ — ~৩ মাস পরের তারিখ; শেষ রক্তদান ৩৫ দিন আগে; ৩৬ বছর · ৫৫ কেজি।', { full: true });
    await openReq(page, 'ডেমো রাজবাড়ি রোড ক্লিনিক');
    await J.shot('জয়দেবপুর বাজার — রক্ত দেব নেই', '‘…থেকে আবার রক্ত দিতে পারবেন’।');
  });

  await J.sc('B-22', 'শেষ রক্তদানের তারিখ পেছানো — ঝুঁকির সতর্কতা', async () => {
    await go(page, '/profile');
    await tap(page, 'শেষ রক্তদানের তারিখ বদলান');
    const d = page.getByRole('dialog').last();
    const six = await page.evaluate(() => new Date(Date.now() - 182 * 864e5 + 6 * 3600e3).toISOString().slice(0, 10));
    await d.locator('input[type=date]').fill(six);
    await settle(page, 300);
    await J.shot('পেছানো তারিখ — স্বাস্থ্যঝুঁকি', 'লাল ‘স্বাস্থ্যঝুঁকি’; টিক না দিলে সেভ হয় না।');
    await d.getByText('আমি জেনেই সিদ্ধান্ত নিয়েছি').click();
    await d.getByRole('button', { name: 'নিশ্চিত করুন' }).click();
    await settle(page, 600);
    await J.shot('সেভের পর — এখন রক্ত দিতে পারবেন', 'নতুন শেষ রক্তদানের তারিখ, ‘এখন রক্ত দিতে পারবেন’।', { full: true });
    await tap(page, 'শেষ রক্তদানের তারিখ বদলান');
    const d2 = page.getByRole('dialog').last();
    const fut = await page.evaluate(() => new Date(Date.now() + 3 * 864e5 + 6 * 3600e3).toISOString().slice(0, 10));
    await d2.locator('input[type=date]').fill(fut);
    await d2.getByRole('button', { name: 'নিশ্চিত করুন' }).click().catch(() => {});
    await settle(page, 300);
    await J.shot('ভবিষ্যতের তারিখ দেওয়া যায় না', '‘ভবিষ্যতের তারিখ দেওয়া যাবে না’।');
    await page.keyboard.press('Escape');
  });

  await J.sc('B-23', 'নিজের ব্লাড ব্যাংক: অনুমোদিত আর অপেক্ষায়, ফোনের হিসাব', async () => {
    page = await J.as('bloodbank');
    await go(page, '/work/bloodbank');
    await J.shot('আমার ব্লাড ব্যাংক', 'জীবনধারা (তালিকায় আছে, ২৪ ঘণ্টা, ০১২০০০০১৩০১) আর রক্তবন্ধু (‘যাচাই চলছে’); ফোনের হিসাব ০ নয়।', { full: true });
  });

  await J.sc('B-24', 'খোলার সময় বদলানো আর ফোন নম্বর বদলানো', async () => {
    const card = page.locator('article, .card, section').filter({ hasText: 'ডেমো জীবনধারা ব্লাড ব্যাংক' }).first();
    await card.getByRole('link', { name: /বদলান/ }).or(card.getByRole('button', { name: /বদলান/ })).first().click();
    await settle(page);
    await J.shot('ব্লাড ব্যাংকের তথ্য বদলান', 'নাম, ধরন, সময়, ফোন, শর্তের টিক।', { full: true });
    const sw = page.getByRole('switch', { name: /২৪ ঘণ্টা/ }).or(page.getByLabel(/২৪ ঘণ্টা/));
    await sw.first().click();
    const opens = page.getByLabel(/খোলে/).first();
    if (await opens.count()) await opens.fill('08:00').catch(() => {});
    const closes = page.getByLabel(/বন্ধ হয়/).first();
    if (await closes.count()) await closes.fill('22:00').catch(() => {});
    const terms = page.getByRole('checkbox').last();
    if (await terms.count()) await terms.check({ force: true });
    await J.shot('সময় সকাল ৮টা–রাত ১০টা', '২৪ ঘণ্টা বন্ধ, খোলে ০৮:০০, বন্ধ ২২:০০, শর্তে টিক।', { full: true });
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await see(page, 'সেভ হয়েছে');
    await J.shot('সময় বদল সেভ হলো', '‘সেভ হয়েছে’ — তালিকায় থেকে যায়।');
  });

  await J.sc('B-25', 'ব্লাড ব্যাংকে ফোন আর রিপোর্ট (ফোন না করে রিপোর্ট যায় না)', async () => {
    page = await J.as('patient');
    await go(page, '/services');
    await tap(page, 'ব্লাড ব্যাংক', { role: 'tab' }).catch(() => tap(page, 'ব্লাড ব্যাংক'));
    await J.shot('রোগী: জীবনধারার নতুন সময়', 'জীবনধারা — সকাল ৮টা–রাত ১০টা।', { full: true });
    await page.locator('article').filter({ hasText: 'ডেমো আলোর পথ ব্লাড ডোনার ক্লাব' }).first().click();
    await settle(page);
    await tap(page, 'রিপোর্ট');
    await settle(page, 400);
    await J.shot('ফোন না করে রিপোর্ট', '‘অ্যাপ থেকে ফোন করার পর রিপোর্ট করতে পারবেন’।');
    await page.keyboard.press('Escape');
  });

  await J.sc('B-27', 'অপেক্ষায় থাকা ব্লাড ব্যাংক কাগজ দেখে অনুমোদন (অ্যাডমিন প্যানেল)', async () => {
    const P = await J.ctxFor('desktop');
    page = P.page;
    J.cur = P;
    if (P.role !== 'owner') {
      if (P.role) await logout(page).catch(() => {});
      await adminLogin(page);
      P.role = 'owner';
    }
    await page.goto(`${BASE}/admin`);
    await settle(page);
    await page.getByText('ব্লাড ব্যাংক', { exact: true }).first().click();
    await settle(page);
    await J.shot('অ্যাডমিন: ব্লাড ব্যাংক — অপেক্ষায়', '‘ডেমো রক্তবন্ধু স্বেচ্ছাসেবী সংগঠন’ — ‘যাচাই করে অনুমোদন’।', { full: true });
    await page.getByText('যাচাই করে অনুমোদন', { exact: true }).first().click();
    await settle(page);
    await J.shot('যাচাইয়ের পাতা — কাগজের ছবি', 'আবেদনে যা লেখা, লাল ‘DEMO … TEST PHOTO’ ছবি, টিকের তালিকা; ‘আরও …টা বিষয় বাকি’।', { full: true });
    const boxes = page.locator('input[type=checkbox]');
    for (let i = 0; i < (await boxes.count()); i++) await boxes.nth(i).check({ force: true }).catch(() => {});
    await settle(page, 300);
    await page.getByRole('button', { name: 'অনুমোদন দিন' }).click();
    await settle(page, 600);
    await J.shot('অনুমোদন দেওয়া হলো', '‘যাচাই করে অনুমোদন দেওয়া হয়েছে’।');
  });

  await J.sc('B-28', 'ব্লাড ব্যাংকের রিপোর্ট দেখা আর রেখে দেওয়া (অ্যাডমিন)', async () => {
    await page.getByText('রিপোর্ট', { exact: true }).first().click();
    await settle(page);
    await J.shot('অ্যাডমিন: রিপোর্ট', '‘ডেমো সদর হাসপাতাল ব্লাড ব্যাংক’ — কারণ ‘ডেমো রিপোর্ট (আসল নয়) — সন্ধ্যা ৭টায় গিয়ে বন্ধ পেয়েছি…’।', { full: true });
    const row = page.locator('tr').filter({ hasText: 'ডেমো সদর হাসপাতাল ব্লাড ব্যাংক' }).first();
    await adminAct(page, row.getByRole('button', { name: 'রেখে দিন' }).first());
    await settle(page, 600);
    await J.shot('রিপোর্ট রেখে দেওয়া হলো', '‘রেখে দেওয়া হয়েছে’ — রিপোর্ট তালিকা থেকে চলে যায়।');
  });

  await J.sc('B-29', 'হাসপাতাল যোগের অনুরোধ (অ্যাডমিন)', async () => {
    await page.getByText('হাসপাতাল', { exact: true }).first().click();
    await settle(page);
    await J.shot('অ্যাডমিন: হাসপাতাল যোগের অনুরোধ', '‘Demo Chayabithi Mother and Child Clinic’ — ধরন ক্লিনিক, গাজীপুর; ‘মেলান’, ‘অনুমোদন’, ‘বাতিল’।', { full: true });
    const row = page.locator('tr').filter({ hasText: /Chayabithi/ }).first();
    await adminAct(page, row.getByRole('button', { name: 'বাতিল' }).first());
    await J.shot('অনুরোধ বাতিল', '‘অনুরোধ বাতিল করা হয়েছে’; তালিকা খালি।');
  });

  await J.sc('B-30', 'জরুরি আহ্বানের তালিকা আর শেষ করা (অ্যাডমিন)', async () => {
    await page.getByText('জরুরি আহ্বান', { exact: true }).first().click();
    await settle(page);
    await J.shot('অ্যাডমিন: জরুরি আহ্বান', 'চালু ‘ডেমো তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল (জরুরি বিভাগ)’ (O+ ৪, A+ ৩, B- ১) আর শেষ হওয়া ‘ডেমো সদর হাসপাতাল, গাজীপুর’।', { full: true });
    await adminAct(page, page.getByRole('button', { name: 'আহ্বান শেষ করুন' }).first());
    const c = page.locator('dialog, [role=dialog]');
    if (await c.count()) await c.last().getByRole('button', { name: /শেষ করুন|হ্যাঁ|নিশ্চিত/ }).last().click().catch(() => {});
    await settle(page, 600);
    await J.shot('আহ্বান শেষ', '‘আহ্বান শেষ, যাঁরা আসছিলেন তাঁদের জানানো হয়েছে’।');
    const P = await J.ctxFor('phone');
    J.cur = P;
    page = await J.as('donor');
    await go(page, '/');
    await J.shot('ডোনারের হোমে আহ্বান নেই', 'জরুরি আহ্বানের কার্ড আর নেই।');
  });

  await J.sc('B-31', 'শুধু ‘ডেমো’ লেখা রিকোয়েস্টে সাড়া দিন', async () => {
    page = await J.as('donor');
    await go(page, '/');
    const names = await page.locator('article').allInnerTexts();
    const real = names.filter((t) => !/ডেমো/.test(t));
    await J.shot('সব রিকোয়েস্টের নামে ‘ডেমো’', 'তালিকার সব হাসপাতালের নাম ‘ডেমো …’ দিয়ে।', real.length ? { status: 'bug', noteBn: `‘ডেমো’ ছাড়া রিকোয়েস্ট: ${real.length}টা` } : {});
  });
}
