// অ্যাম্বুলেন্স (গাইড journey-amb.json J-81…J-84 আর services.json S-80…S-82, S-23-এর অংশ): কাছের খালি, ফোন, রেটিং, চালকের দিক
import { tap, see, go, settle } from '../lib.mjs';

export const meta = { no: 3, slug: 'ambulance', titleBn: 'অ্যাম্বুলেন্স: সবচেয়ে কাছের, ফোন, রেটিং' };

const AC = 'ডেমো জীবন অ্যাম্বুলেন্স (এসি)';

async function ambList(page) {
  await go(page, '/services/ambulance');
}

async function filterType(page, label) {
  await tap(page, 'ফিল্টার');
  const d = page.getByRole('dialog').last();
  await d.getByText(label, { exact: true }).first().click();
  const show = d.getByRole('button', { name: 'দেখান' });
  if (await show.count()) await show.click();
  await settle(page);
}

async function clearFilter(page) {
  const x = page.getByRole('button', { name: /সরান/ });
  if (await x.count()) await x.first().click();
  await settle(page);
}

export async function run(J) {
  let page;

  await J.sc('J-81 S-80', 'রোগী গাজীপুরে অ্যাম্বুলেন্স খোঁজেন', async () => {
    page = await J.as('patient');
    await ambList(page);
    await J.shot('সেবা › অ্যাম্বুলেন্স (রোগী)', 'ওপরে বড় বাটন ‘সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন’ (ডেমো জীবন অ্যাম্বুলেন্স (এসি) · এলাকা ধরে · এসি, সাধারণ), নিচে ‘জরুরি হলে ৯৯৯…’; তালিকায় খালিগুলো আগে।', { full: true });
    await see(page, 'ফোন: সবচেয়ে কাছের খালি');
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
    await page.locator('article').filter({ hasText: AC }).first().click();
    await settle(page);
    await J.shot('ডেমো জীবন অ্যাম্বুলেন্স (এসি) — বিস্তারিত', '‘গাড়ি’, ‘এলাকা ও ভাড়া’ (তাজউদ্দীন মেডিকেল গেট, শিববাড়ি), ‘এখনকার অবস্থা’ — ‘খালি জানিয়েছেন … আগে’; রেটিং ৪.৭ (৯)।', { full: true });
  });

  await J.sc('J-83', 'নম্বর কপি / ফোন করা', async () => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {});
    // ২.৩x: বিস্তারিতে আর ‘কপি’ বোতাম নেই — শুধু নম্বর আর ‘ফোন’ (থাকলে আগের মতো কপি)
    const copy = page.getByRole('button', { name: 'কপি' }).first();
    if (await copy.count()) {
      await copy.click();
      await see(page, 'কপি হয়েছে');
      await J.shot('নম্বর কপি', '‘কপি হয়েছে’; নম্বর 01700000170।');
    }
    const call = page.locator('a[href^="tel:"]').filter({ hasText: /^ফোন/ }).first();
    const href = await call.getAttribute('href');
    if (!/01700000170/.test(href || '')) throw new Error(`ফোনের লিংক ভুল: ${href}`);
    // tel: লিংক — ব্রাউজারে আসল ফোন হয় না; চাপা গোনা হয় (মালিকের হিসাবে)
    await call.click({ noWaitAfter: true }).catch(() => {});
    await settle(page);
    await J.shot('ফোন করুন চাপা (tel:01700000170)', 'ফোনের লিংক ০১৭০০০০০১৭০; অ্যাপ থেকে ফোন হিসেবে গোনা হয়।');
  });

  await J.sc('S-81', 'রোগী: বড় বাটনে এক চাপে ফোন', async () => {
    await ambList(page);
    const big = page.locator('.amb-call-phone, a[href^="tel:"]').first();
    const href = await big.getAttribute('href').catch(() => null);
    await big.click({ noWaitAfter: true }).catch(() => {});
    await settle(page);
    await J.shot('বড় বাটনে এক চাপ', `এক চাপেই ডায়ালার — নম্বর ০১৭০০০০০১৭০${href ? ` (লিংক ${href})` : ''}।`);
  });

  await J.sc('J-84', 'চালকের দিক: ফোনের হিসাব, রেটিং, খালি/ট্রিপে', async () => {
    page = await J.as('ambulance');
    await go(page, '/work');
    await J.shot('চালক: আমার কাজ', 'অ্যাম্বুলেন্সের সারি: ৪টা · ২টা খালি · ১টা যাচাই চলছে।');
    await go(page, '/work/ambulance');
    await J.shot('চালক: আমার অ্যাম্বুলেন্স', 'এসি (খালি, সুইচ চালু), মিনি (খালি — আর … মিনিট), নন-এসি (ট্রিপে), আইসিইউ (‘অনুমোদনের অপেক্ষায়’, সুইচ নেই); অ্যাপ থেকে ফোনের সংখ্যা।', { full: true });
    const card = page.locator('article, .card').filter({ hasText: AC }).first();
    const det = card.getByRole('button', { name: 'বিস্তারিত' });
    if (await det.count()) {
      await det.first().click();
      await settle(page);
      await J.shot('এসি গাড়ির হিসাব — বিস্তারিত', 'গত ৩০ দিনের দাগ, রেটিং আর ‘সাম্প্রতিক রেটিং’-এ দুটো মন্তব্য (‘রাত ২টায় ফোন করেছিলাম…’), কে দিয়েছেন দেখায় না।');
      await page.keyboard.press('Escape');
    }
    const nonac = page.locator('article, .card').filter({ hasText: 'ডেমো জীবন অ্যাম্বুলেন্স (নন-এসি)' }).first();
    await nonac.getByRole('switch').first().click();
    await settle(page, 600);
    await J.shot('নন-এসি: এখন খালি আছি', 'নন-এসি গাড়ির সুইচ চালু — ‘এখন খালি আছি’।');
    page = await J.as('patient');
    await ambList(page);
    await J.shot('রোগীর তালিকায় নন-এসি এখন খালি', 'নন-এসি গাড়ি ‘এখন ট্রিপে আছে’ থেকে উঠে মূল তালিকায় ‘খালি আছে’।', { full: true });
  });

  await J.sc('S-23', 'রোগীর রেটিং: অ্যাম্বুলেন্স', async () => {
    const icu = page.locator('article').filter({ hasText: 'ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস' }).first();
    await icu.getByRole('button', { name: 'রেটিং দিন' }).click();
    const d = page.getByRole('dialog').last();
    await d.getByRole('button', { name: /^৪|4/ }).first().click().catch(async () => { await d.locator('[aria-label*="৪"]').first().click(); });
    const c = d.getByLabel('মন্তব্য (ঐচ্ছিক)');
    if (await c.count()) await c.fill('ডেমো: সময়মতো এসেছিল');
    await J.shot('অ্যাম্বুলেন্সে রেটিং — ৪ তারা', '৪ তারা আর মন্তব্য।');
    await d.getByRole('button', { name: 'জমা দিন' }).click();
    await see(page, 'রেটিং দেওয়ার জন্য ধন্যবাদ');
    await J.shot('রেটিং জমা হলো', '‘রেটিং দেওয়ার জন্য ধন্যবাদ!’');
    const mom = page.locator('article').filter({ hasText: 'ডেমো মমতা অ্যাম্বুলেন্স' }).first();
    const has = await mom.getByRole('button', { name: 'রেটিং দিন' }).count();
    await J.shot('ফোন না করা গাড়িতে রেটিং নেই', '‘ডেমো মমতা অ্যাম্বুলেন্স’-এ ‘রেটিং দিন’ নেই।', has ? { status: 'bug', noteBn: 'ফোন না করা গাড়িতেও ‘রেটিং দিন’ দেখা যাচ্ছে' } : {});
  });

  await J.sc('S-84', 'অতিথি: ডেমো গাড়ি বড় বাটনে আসে না', async () => {
    page = await J.as('guest', 'desktop');
    await ambList(page);
    const choose = page.getByRole('button', { name: /এলাকা বেছে নিন/ }).first();
    if (await choose.count()) {
      await choose.click();
      const { pickDistrict } = await import('./02-blood.mjs');
      await pickDistrict(page);
    }
    await J.shot('অতিথি: অ্যাম্বুলেন্স — বড় বাটন নেই', 'অতিথির পাতায় ‘সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন’ নেই; কার্ডে ‘রেটিং দিন’ নেই; আইসিইউ জীবন (অপেক্ষায়) নেই।', { full: true });
    if (await page.getByText('সবচেয়ে কাছের খালি অ্যাম্বুলেন্সে ফোন').count()) throw new Error('অতিথি বড় বাটন দেখছেন');
  });
}
