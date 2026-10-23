// অ্যাম্বুলেন্স (গাইড journey-amb.json J-81…J-84 আর services.json S-80…S-82, S-23-এর অংশ): কাছের খালি, ফোন, রেটিং, চালকের দিক
import { tap, see, go, settle } from '../lib.mjs';

export const meta = { no: 3, slug: 'ambulance', titleBn: 'অ্যাম্বুলেন্স: সবচেয়ে কাছের, ফোন, রেটিং' };

const AC = 'ডেমো জীবন অ্যাম্বুলেন্স (এসি)';

async function ambList(page) {
  await go(page, '/services/ambulance');
}

/** সেবার তালিকার এক ছাঁচ: ধরন এখন ছাঁকনির চিপ (এক লাইন) */
async function filterType(page, label) {
  await page.getByTestId('filter-chips').getByRole('button', { name: label, exact: true }).first().click();
  await settle(page);
}

async function clearFilter(page) {
  const x = page.getByTestId('filter-clear');
  if (await x.count()) await x.first().click();
  await settle(page);
}

/** কার্ড খুলে বিস্তারিত (ফোনে পুরো পাতা) */
async function openCard(page, name) {
  await page.getByTestId('listing-card').filter({ hasText: name }).first().click();
  await settle(page);
}

export async function run(J) {
  let page;

  await J.sc('J-81 S-80', 'রোগী গাজীপুরে অ্যাম্বুলেন্স খোঁজেন', async () => {
    page = await J.as('patient');
    await ambList(page);
    await J.shot('সেবা › অ্যাম্বুলেন্স (রোগী)', 'ওপরে বড় ‘অ্যাম্বুলেন্স ডাকুন’ আর পাশে ‘ফোন’ (সবচেয়ে কাছের খালি), নিচে এক লাইনে ‘জরুরি হলে ৯৯৯…’; খোঁজা, এলাকার চিপ, সাজানো, ধরনের চিপ; কার্ডে ‘এসি/সাধারণ · দূরত্ব · ★’, ‘খালি আছে’, ‘ফোন করুন’।', { full: true });
    await see(page, 'অ্যাম্বুলেন্স ডাকুন');
    if ((await page.locator('a[href="tel:999"]').count()) !== 1) throw new Error('৯৯৯ একবারের বেশি');
    const trip = page.getByText(/এখন ট্রিপে আছে/).first();
    if (await trip.count()) {
      await trip.click();
      await settle(page);
      await J.shot('এখন ট্রিপে আছে — খোলা', 'ভাঁজ খুললে ‘ডেমো জীবন অ্যাম্বুলেন্স (নন-এসি)’।', { full: true });
    }
  });

  await J.sc('J-82 S-82', 'ধরন দিয়ে ছাঁকা আর ডেমো অ্যাম্বুলেন্স খোলা', async () => {
    await filterType(page, 'এসি');
    await J.shot('ফিল্টার: এসি', 'শুধু এসি গাড়ি: ‘ডেমো জীবন অ্যাম্বুলেন্স (এসি)’ আর ‘ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস’।', { full: true });
    await clearFilter(page);
    await filterType(page, 'আইসিইউ');
    await J.shot('ফিল্টার: আইসিইউ', 'শুধু ‘ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস’ (‘ফোনে জেনে নিন’); বড় বাটন নেই।', { full: true });
    await clearFilter(page);
    await filterType(page, 'ফ্রিজার ভ্যান');
    await J.shot('ফিল্টার: ফ্রিজার ভ্যান', 'বাটনে ‘ডেমো শেষ যাত্রা ফ্রিজার ভ্যান · এলাকা ধরে · ফ্রিজার ভ্যান’।', { full: true });
    await clearFilter(page);
    await openCard(page, AC);
    await J.shot('ডেমো জীবন অ্যাম্বুলেন্স (এসি) — বিস্তারিত', '‘মূল তথ্য’ (‘খালি জানিয়েছেন … আগে’, ভাড়া), ‘গাড়ি’, ‘ঠিকানা ও ফোন’ (তাজউদ্দীন মেডিকেল গেট, শিববাড়ি; নম্বর কপি), ‘রেটিং’ ৪.৭ (৯); নিচে আটকানো ‘অ্যাম্বুলেন্স ডাকুন’ + কলের আইকন।', { full: true });
  });

  await J.sc('J-83', 'নম্বর কপি / ফোন করা', async () => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {});
    // এক ছাঁচের বিস্তারিত: ‘ঠিকানা ও ফোন’-এ নম্বর আর ‘কপি’; নিচের বারে কলের আইকন (ফোন কল)
    const copy = page.getByRole('button', { name: 'কপি' }).first();
    if (await copy.count()) {
      await copy.click();
      await see(page, 'কপি হয়েছে');
      await J.shot('নম্বর কপি', '‘কপি হয়েছে’; নম্বর 01200000170।');
    }
    const call = page.getByTestId('detail-bar').locator('a[href^="tel:"]').first();
    const href = await call.getAttribute('href');
    if (!/01200000170/.test(href || '')) throw new Error(`ফোনের লিংক ভুল: ${href}`);
    // tel: লিংক — ব্রাউজারে আসল ফোন হয় না; চাপা গোনা হয় (মালিকের হিসাবে)
    await call.click({ noWaitAfter: true }).catch(() => {});
    await settle(page);
    await J.shot('ফোন করুন চাপা (tel:01200000170)', 'ফোনের লিংক ০১২০০০০০১৭০; অ্যাপ থেকে ফোন হিসেবে গোনা হয়।');
  });

  await J.sc('S-81', 'রোগী: বড় বাটনে এক চাপে ফোন', async () => {
    await ambList(page);
    const big = page.locator('.amb-call-phone, a[href^="tel:"]').first();
    const href = await big.getAttribute('href').catch(() => null);
    await big.click({ noWaitAfter: true }).catch(() => {});
    await settle(page);
    await J.shot('বড় বাটনে এক চাপ', `এক চাপেই ডায়ালার — নম্বর ০১২০০০০০১৭০${href ? ` (লিংক ${href})` : ''}।`);
  });

  await J.sc('J-84', 'চালকের দিক: ফোনের হিসাব, রেটিং, খালি/ট্রিপে', async () => {
    page = await J.as('ambulance');
    await go(page, '/work');
    await J.shot('চালক: আজকের কাজ', '‘অ্যাম্বুলেন্স’ অংশে প্রতিটা গাড়ির ‘খালি আছি / ট্রিপে আছি’ (আইসিইউ ‘যাচাই চলছে’), ‘নতুন ডাক’; নিচে ‘আমার অ্যাম্বুলেন্স’ — ৪টা · ২টা খালি · ১টা যাচাই চলছে।');
    await go(page, '/work/ambulance');
    await J.shot('চালক: আমার অ্যাম্বুলেন্স', 'উপরে ‘ডাক’ (না থাকলে এক লাইন); প্রতিটা চালু গাড়ির ‘রোগীরা আপনাকে যেভাবে দেখেন’-এ ‘খালি আছি / ট্রিপে আছি’ (মিনি: খালি — আর … মিনিট); গাড়ির তালিকায় শুধু অবস্থা — এসি খালি, নন-এসি ট্রিপে, আইসিইউ ‘অনুমোদনের অপেক্ষায়’; অ্যাপ থেকে ফোনের সংখ্যা।', { full: true });
    // "রোগীরা আপনাকে যেভাবে দেখেন"-এর কার্ড নয় — হিসাবের "বিস্তারিত" আছে এমন কার্ড
    const card = page.locator('article, .card').filter({ hasText: AC }).filter({ has: page.getByRole('button', { name: 'বিস্তারিত' }) }).first();
    const det = card.getByRole('button', { name: 'বিস্তারিত' });
    if (await det.count()) {
      await det.first().click();
      await settle(page);
      await J.shot('এসি গাড়ির হিসাব — বিস্তারিত', 'গত ৩০ দিনের দাগ, রেটিং আর ‘সাম্প্রতিক রেটিং’-এ দুটো মন্তব্য (‘রাত ২টায় ফোন করেছিলাম…’), কে দিয়েছেন দেখায় না।');
      await page.keyboard.press('Escape');
    }
    // "রোগীরা আপনাকে যেভাবে দেখেন" — প্রতিটা চালু গাড়ির কার্ড আর এক চাপে "খালি আছি / ট্রিপে আছি"
    const nonac = page.getByTestId('provider-preview').filter({ hasText: 'ডেমো জীবন অ্যাম্বুলেন্স (নন-এসি)' }).first();
    await nonac.getByTestId('sw-available').getByRole('button', { name: 'খালি আছি' }).click();
    await settle(page, 600);
    await nonac.getByText('খালি আছে').first().waitFor({ timeout: 8000 });
    await J.shot('নন-এসি: এখন খালি আছি', 'নন-এসি গাড়ির ‘রোগীরা আপনাকে যেভাবে দেখেন’ কার্ডে ‘খালি আছে’, সুইচ ‘খালি আছি’।');
    page = await J.as('patient');
    await ambList(page);
    await J.shot('রোগীর তালিকায় নন-এসি এখন খালি', 'নন-এসি গাড়ি ‘এখন ট্রিপে আছে’ থেকে উঠে মূল তালিকায় ‘খালি আছে’।', { full: true });
  });

  await J.sc('S-23', 'রোগীর রেটিং: অ্যাম্বুলেন্স', async () => {
    // রেটিং এখন বিস্তারিতের ‘রেটিং’ ভাগে
    await openCard(page, 'ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস');
    await page.getByRole('button', { name: 'রেটিং দিন' }).first().click();
    const d = page.getByRole('dialog').last();
    await d.getByRole('button', { name: /^৪|4/ }).first().click().catch(async () => { await d.locator('[aria-label*="৪"]').first().click(); });
    const c = d.getByLabel('মন্তব্য (ঐচ্ছিক)');
    if (await c.count()) await c.fill('ডেমো: সময়মতো এসেছিল');
    await J.shot('অ্যাম্বুলেন্সে রেটিং — ৪ তারা', '৪ তারা আর মন্তব্য।');
    await d.getByRole('button', { name: 'জমা দিন' }).click();
    await see(page, 'রেটিং দেওয়ার জন্য ধন্যবাদ');
    await J.shot('রেটিং জমা হলো', '‘রেটিং দেওয়ার জন্য ধন্যবাদ!’');
    await ambList(page);
    await openCard(page, 'ডেমো মমতা অ্যাম্বুলেন্স');
    const has = await page.getByRole('button', { name: 'রেটিং দিন' }).count();
    await J.shot('ফোন না করা গাড়িতে রেটিং নেই', '‘ডেমো মমতা অ্যাম্বুলেন্স’-এ ‘রেটিং দিন’ নেই।', has ? { status: 'bug', noteBn: 'ফোন না করা গাড়িতেও ‘রেটিং দিন’ দেখা যাচ্ছে' } : {});
  });

  await J.sc('S-84', 'অতিথি: ডেমো গাড়ি বড় বাটনে আসে না', async () => {
    page = await J.as('guest', 'desktop');
    await ambList(page);
    const choose = page.getByTestId('area-chip');
    if (!(await page.getByTestId('listing-card').count()) && (await choose.count())) {
      await choose.click();
      const { pickDistrict } = await import('./02-blood.mjs');
      await pickDistrict(page);
    }
    await J.shot('অতিথি: অ্যাম্বুলেন্স — বড় বাটন নেই', 'অতিথির পাতায় ‘সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন’ নেই; কার্ডে ‘রেটিং দিন’ নেই; আইসিইউ জীবন (অপেক্ষায়) নেই; ডেস্কটপে ডানে প্রথম গাড়ির বিস্তারিত।', { full: true });
    if (await page.getByText('সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন').count()) throw new Error('অতিথি বড় বাটন দেখছেন');
  });
}
