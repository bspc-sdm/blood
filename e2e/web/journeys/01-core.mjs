// রোগীর পুরো যাত্রা (গাইড journey.json J-01…J-25): সিরিয়াল → সারি → প্রেসক্রিপশন → ফার্মেসি → ল্যাব → রিপোর্ট দেখানোর টাকা → ডাক্তার
import { tap, has, see, go, settle, dhakaISO, bn } from '../lib.mjs';

export const meta = { no: 1, slug: 'core-journey', titleBn: 'রোগীর পুরো যাত্রা: সিরিয়াল থেকে রিপোর্ট' };

const DOC = 'ডা. ডেমো হাসান';
const ME = 'ডেমো রোগী রহিম';

/** প্রোফাইল › আমার অ্যাপয়েন্টমেন্ট › ডা. ডেমো হাসান (আজকেরটা) */
async function openMyAppt(page) {
  await go(page, '/my-appointments');
  // "সামনের" ভাগের প্রথম ডা. ডেমো হাসান = আজকেরটা
  await page.locator('article').filter({ hasText: DOC }).first().click();
  await settle(page);
}

/** আমার কাজ › আমার চেম্বার › বুকিং › আজ */
async function chamberToday(page) {
  await go(page, '/work');
  await tap(page, 'আমার চেম্বার');
  await tap(page, 'বুকিং', { role: 'tab' });
  await tap(page, /^আজ/, { role: 'tab' });
}

export async function run(J) {
  let page;

  await J.sc('J-01', 'রোগী: ডেমো ডাক্তারের সিরিয়াল চাওয়া', async () => {
    page = await J.as('patient');
    await J.shot('রোগী হিসেবে ঢোকা (লগইন পাতার ডেমো তালিকা থেকে)', 'হোম পাতায় আশেপাশের রক্তের রিকোয়েস্ট দেখা যায়।');
    await tap(page, 'অ্যাপয়েন্টমেন্ট', { role: 'link', exact: true });
    await J.shot('অ্যাপয়েন্টমেন্ট › ডাক্তারের তালিকা', 'ময়মনসিংহের ডেমো ডাক্তারদের তালিকা, ‘ডা. ডেমো হাসান’ আছেন।');
    await tap(page, DOC);
    await J.shot('ডা. ডেমো হাসানের পাতা', 'চেম্বার ‘ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল’, রুম ‘ডেমো চেম্বার · বহির্বিভাগ ৩১২’, ফি ৫০০ টাকা, ‘অ্যাপয়েন্টমেন্ট চাই’ বোতাম।');
    // প্রথম চেম্বার = ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল
    await page.getByRole('button', { name: 'অ্যাপয়েন্টমেন্ট চাই' }).first().click();
    const dlg = page.getByRole('dialog');
    await dlg.waitFor();
    await dlg.locator('input[type=date]').fill(dhakaISO(0));
    await dlg.getByRole('textbox').last().fill('সুগার বেশি, মাথা ঘোরে');
    await J.shot('সিরিয়ালের অনুরোধের ঘর ভরা', 'তারিখ ‘আজ’ বাছা, ‘কী সমস্যা’-তে লেখা।');
    await dlg.getByRole('button', { name: 'অনুরোধ পাঠান' }).click();
    await see(page, 'অনুরোধ পাঠানো হয়েছে');
    await J.shot('অনুরোধ পাঠানো হলো', '‘অনুরোধ পাঠানো হয়েছে। নিশ্চিত হলে নোটিফিকেশন পাবেন।’ লেখা আসে।');
    await go(page, '/my-appointments');
    await J.shot('আমার অ্যাপয়েন্টমেন্ট — অপেক্ষায়', '‘ডা. ডেমো হাসান’ — অবস্থা ‘অপেক্ষায়’, সিরিয়াল নম্বর নেই, ডাক্তারের ফোন নেই।');
  });

  await J.sc('J-02', 'ডাক্তার: আজকের সিরিয়ালে রোগীকে নিশ্চিত করা', async () => {
    page = await J.as('doctor');
    await chamberToday(page);
    await J.shot('ডাক্তার: আমার চেম্বার › বুকিং › আজ', 'আজকের সিরিয়াল কার্ড (এখন দেখছি ২, পরের ৩, গড় ৮ মি.), সালমা, করিম, রাশেদুল, মরিয়ম (২০ মিনিট দেরি), অপেক্ষায় হাবিবুর আর রহিম।', { full: true });
    const row = page.locator('tr, .row, article, li').filter({ hasText: ME }).filter({ has: page.getByRole('button', { name: 'নিশ্চিত করুন' }) }).last();
    await row.getByRole('button', { name: 'নিশ্চিত করুন' }).click();
    const dlg = page.getByRole('dialog');
    await dlg.waitFor();
    await dlg.getByRole('textbox').last().fill('সিরিয়াল এলে ফোন করা হবে');
    await J.shot('নিশ্চিত করার ঘর', 'সিরিয়াল নম্বর খালি, রোগীর জন্য নোট লেখা।');
    await dlg.getByRole('button', { name: 'নিশ্চিত করুন' }).click();
    await settle(page, 600);
    await J.shot('রহিম নিশ্চিত — সিরিয়াল ৫', 'রহিমের সারি ‘নিশ্চিত’, সিরিয়াল ৫, ফোন বোতাম দেখা যায়।', { full: true });
  });

  await J.sc('J-03', 'রোগী: সিরিয়াল নম্বর আর ডাক্তারের ফোন দেখা', async () => {
    page = await J.as('patient');
    await go(page, '/my-appointments');
    await J.shot('রোগী: আমার অ্যাপয়েন্টমেন্ট — নিশ্চিত, সিরিয়াল ৫', 'কার্ডে ‘সিরিয়াল নং ৫’ আর আলাদা লাইনে ‘এখন চলছে: ২ · আপনার আগে …’।', { status: 'fixed', noteBn: 'আগে কার্ডে ‘সিরিয়াল নং ৫’ আর ‘এখন চলছে…’ একই লাইনে জোড়া লেগে ‘সিরিয়াল নং ৫এখন চলছে’ দেখাত (কার্ডের CSS-এ লাইন ভাঙছিল না)। ঠিক করা হয়েছে।' });
    await openMyAppt(page);
    await J.shot('রোগী: নিশ্চিত অ্যাপয়েন্টমেন্ট', 'অবস্থা ‘নিশ্চিত’, নোট ‘সিরিয়াল এলে ফোন করা হবে’, ‘আপনার সিরিয়াল’ ৫, ডাক্তারের মোবাইল ০১৭০০০০১১০৩।', { full: true });
  });

  await J.sc('J-04', 'সহকারী: আজকের সিরিয়ালের তালিকা দেখা', async () => {
    page = await J.as('assistant');
    await go(page, '/work');
    await J.shot('সহকারী: আমার কাজ', '‘প্রেসক্রিপশন’ কার্ড — ডাক্তারের সহকারী · ডা. ডেমো হাসান।');
    await openRx(page);
    await J.shot('সহকারী: রোগী — আজকের সিরিয়াল আর অ্যাপের অ্যাপয়েন্টমেন্ট', 'ওপরে ‘আজকের সিরিয়াল’ কার্ড (পরের রোগী, বিরতি), নিচে ‘অ্যাপের অ্যাপয়েন্টমেন্ট (আজ–কাল)’-এ রহিম ‘সিরিয়াল ৫’; ট্যাব শুধু ‘রোগী’ আর ‘টাকা মেলানো’ — ‘নতুন প্রেসক্রিপশন’ নেই।', { full: true });
    if (await page.getByRole('tab', { name: /নতুন প্রেসক্রিপশন/ }).count()) throw new Error('সহকারীর জন্য নতুন প্রেসক্রিপশন ট্যাব দেখা যাচ্ছে');
  });

  await J.sc('J-05', 'ডাক্তার: ওষুধ আর পরীক্ষাসহ প্রেসক্রিপশন লেখা', async () => {
    page = await J.as('doctor');
    await openRx(page);
    await J.shot('ডাক্তার: প্রেসক্রিপশন › রোগী', '‘অ্যাপের অ্যাপয়েন্টমেন্ট (আজ–কাল)’-এ ‘ডেমো রোগী রহিম’ সিরিয়াল ৫, পাশে ‘লিখুন’।');
    const row = page.locator('tr').filter({ hasText: ME }).filter({ hasText: 'সিরিয়াল ৫' }).first();
    await row.getByText('লিখুন').click();
    await settle(page);
    const chamber = page.getByLabel('চেম্বার');
    if (await chamber.count()) await chamber.selectOption({ label: 'ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল' }).catch(() => {});
    await page.getByLabel('সমস্যা', { exact: true }).fill('সুগার বেশি, মাথা ঘোরে');
    await page.getByLabel('প্রেসার', { exact: true }).fill('140/90');
    await page.getByLabel('রোগনির্ণয়', { exact: true }).fill('Type 2 DM');
    await fillMed(page, 0, { name: 'Comet', strength: '500 mg', dose: '1+0+1', days: '30' });
    await page.getByRole('button', { name: 'ওষুধ যোগ করুন' }).click();
    await fillMed(page, 1, { name: 'Napa', strength: '500 mg', dose: '1+1+1', days: '3' });
    await page.getByText('CBC', { exact: true }).first().click();
    await page.getByText('HbA1c', { exact: true }).first().click();
    await page.getByText('১৫ দিন পর', { exact: true }).click();
    await J.shot('প্রেসক্রিপশন লেখা — সব ঘর ভরা', 'রোগী রহিম, সমস্যা, প্রেসার 140/90, রোগনির্ণয় Type 2 DM, Comet আর Napa, পরীক্ষা CBC আর HbA1c, ১৫ দিন পর।', { full: true });
    await page.getByRole('button', { name: 'প্রেসক্রিপশন দিন' }).click();
    const dlg = page.getByRole('dialog');
    await dlg.waitFor();
    await J.shot('দেওয়ার আগে শেষবার দেখা', '‘প্রেসক্রিপশনটা দেবেন?’ — কাগজের মতো খসড়া, ‘আবার দেখি’ আর ‘দিন’।');
    await dlg.getByRole('button', { name: 'দিন', exact: true }).click();
    await see(page, 'DEMO-A-10001', 10000);
    await J.shot('প্রেসক্রিপশন দেওয়া হয়েছে — কাগজ', 'প্রেসক্রিপশনের কাগজ খোলে: ১০ অক্ষরের কোড, QR, রেজি. DEMO-A-10001।', { full: true });
  });

  await J.sc('J-06', 'রোগী: নতুন প্রেসক্রিপশন দেখা ও ছাপা', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await J.shot('রোগী: আজকের প্রেসক্রিপশন', 'Comet আর Napa, ‘২টা পরীক্ষা’ (CBC, HbA1c), ‘আবার দেখাবেন’ ১৫ দিন পর, ‘সিরিয়াল নিন’; ‘ওষুধ কেনা’-তে দুটোই ‘এখনো কেনা হয়নি’।', { full: true });
    await page.emulateMedia({ media: 'print' });
    await J.shot('ছাপার রূপ (প্রিন্ট)', 'ছাপার কাগজে ডাক্তারের নাম, চেম্বার, কোড আর যাচাইয়ের QR; মেনু/বোতাম থাকে না।', { full: true });
    await page.emulateMedia({ media: 'screen' });
  });

  await J.sc('J-07', 'রোগী: ডেমো লাইফ ফার্মেসিতে অনলাইনে অর্ডার', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await page.getByRole('button', { name: 'ফার্মেসিতে পাঠান' }).last().click();
    await page.getByRole('dialog').waitFor();
    await J.shot('ফার্মেসিতে পাঠানোর দুই পথ', '‘দোকানে আছি — এই দোকানে পাঠান’ আর ‘অনলাইনে অর্ডার — বাসায় ডেলিভারি’।');
    await tap(page, 'অনলাইনে অর্ডার — বাসায় ডেলিভারি');
    await pickAreaIfAsked(page);
    await see(page, 'ডেমো লাইফ ফার্মেসি');
    await J.shot('অনলাইনে অর্ডার — ফার্মেসি বাছাই', '‘ডেমো লাইফ ফার্মেসি’ কার্ডে ‘২টার ২টা ওষুধ আছে’, ডেলিভারি ৩০ টাকা।');
    const card = page.getByRole('dialog').locator('article, .card').filter({ hasText: 'ডেমো লাইফ ফার্মেসি' }).first();
    await card.getByRole('button', { name: 'অর্ডার করুন' }).click();
    const dlg = page.getByRole('dialog').last();
    await dlg.getByLabel('কোথায় পৌঁছাবে (ঠিকানা)').fill('ডেমো বাসা ৫, চরপাড়া');
    const ph = dlg.getByLabel(/মোবাইল/);
    if (await ph.count()) await ph.first().fill('01700001101');
    await J.shot('অর্ডারের ঠিকানা আর ফোন', 'ঠিকানা ‘ডেমো বাসা ৫, চরপাড়া’, মোবাইল 01700001101; আনুমানিক দাম আর ডেলিভারি চার্জ।');
    await dlg.getByRole('button', { name: 'অর্ডার দিন' }).click();
    await see(page, 'অর্ডার গেছে');
    await J.shot('অর্ডার গেছে', '‘ডেমো লাইফ ফার্মেসি-এ অর্ডার গেছে। ওষুধ পৌঁছালে দাম দেবেন।’; অবস্থা ‘পাঠানো হয়েছে — অপেক্ষায়’, পাশে ‘ফিরিয়ে নিন’।', { full: true });
  });

  await J.sc('J-08', 'ফার্মেসি: অর্ডার নেওয়া আর পাঠানো', async () => {
    page = await J.as('pharmacy');
    await pharmacyOrders(page, 'অনলাইন অর্ডার');
    await J.shot('ফার্মেসি: অনলাইন অর্ডার — চালু', '‘ডেমো রোগী রহিম’ — ঠিকানা ‘ডেমো বাসা ৫, চরপাড়া’, ফোন, ‘খুলে ওষুধ লিখুন’, ‘অর্ডার নিলাম’, ‘পাঠিয়ে দিয়েছি’, ‘পারব না’ — সব বোতাম পুরো দেখা যায়।', { status: 'fixed', noteBn: 'আগে ১৩৬৬ পিক্সেল চওড়া ল্যাপটপ পর্দায় অনলাইন অর্ডারের ছকের ডান দিক কেটে যেত — ‘অর্ডার নিলাম’, ‘পারব না’ বোতাম অর্ধেক দেখা যেত, পাশে টেনে দেখতে হতো। এখন বড় পর্দা (১৬০০+) ছাড়া কার্ডে দেখায়।' });
    const row = page.locator('tr, article').filter({ hasText: 'ডেমো বাসা ৫, চরপাড়া' }).first();
    await row.getByRole('button', { name: 'অর্ডার নিলাম' }).click();
    const dlg = page.getByRole('dialog');
    await dlg.getByLabel('মোট দাম (ঐচ্ছিক)').fill('৪২০ টাকা');
    await dlg.getByLabel('কখন পৌঁছাবে (ঐচ্ছিক)').fill('এক ঘণ্টায়');
    await J.shot('অর্ডার নিন — দাম আর সময়', 'মোট দাম ৪২০ টাকা, কখন পৌঁছাবে ‘এক ঘণ্টায়’।');
    await dlg.getByRole('button', { name: 'নিলাম' }).click();
    await see(page, 'রোগীকে জানানো হয়েছে');
    await J.shot('অর্ডার নেওয়া হলো', '‘রোগীকে জানানো হয়েছে’; সারিতে ‘৪২০ টাকা · এক ঘণ্টায়’।');
    await row.getByRole('button', { name: 'পাঠিয়ে দিয়েছি' }).click();
    await settle(page, 500);
    await J.shot('পাঠিয়ে দেওয়া হলো', 'অবস্থা ‘পথে আছে’।');
  });

  await J.sc('J-09', 'ফার্মেসি: কত ওষুধ দিলেন লেখা (অর্ডার সম্পন্ন)', async () => {
    page = await J.as('pharmacy');
    await pharmacyOrders(page, 'অনলাইন অর্ডার');
    const row = page.locator('tr, article').filter({ hasText: 'ডেমো বাসা ৫, চরপাড়া' }).first();
    await row.getByRole('button', { name: 'খুলে ওষুধ লিখুন' }).click();
    await settle(page);
    await J.shot('প্রেসক্রিপশন খোলা — কয়টা দিলেন', 'Comet (৬০টা বাকি) আর Napa (৯টা বাকি) — ‘কয়টা দিলেন’ ঘর আর ‘পুরো’ বোতাম; ওষুধের নিচে ‘খাবারের পরে’।', { status: 'fixed', noteBn: 'আগে ওষুধের নামের নিচে ইংরেজিতে ‘after’ দেখাত (খাওয়ার সময়ের কাঁচা মান)। এখন ‘খাবারের পরে’/‘খাবারের আগে’।' });
    // বেশি দিলে আটকায়
    await page.getByLabel('Comet: কয়টা দিলেন').fill('70');
    await page.getByRole('button', { name: 'যা দিলাম লিখে রাখুন' }).click();
    await settle(page, 500);
    await J.shot('৬০-এর বেশি লিখলে আটকায়', '‘Comet: এই প্রেসক্রিপশনে আর …টা বাকি’ ধরনের ভুলের লেখা।');
    await page.locator('tr').filter({ has: page.getByLabel('Comet: কয়টা দিলেন') }).getByRole('button', { name: 'পুরো' }).click();
    await page.getByLabel('Napa: কয়টা দিলেন').fill('9');
    await J.shot('Comet পুরো ৬০, Napa ৯', 'Comet-এর ঘরে ৬০, Napa-র ঘরে ৯।');
    await page.getByRole('button', { name: 'যা দিলাম লিখে রাখুন' }).click();
    await see(page, 'লেখা হয়েছে');
    await J.shot('লেখা হয়েছে — দেওয়া শেষ', '‘লেখা হয়েছে’; দুটো ওষুধই ‘দেওয়া শেষ’।');
    await pharmacyOrders(page, 'অনলাইন অর্ডার');
    await tap(page, 'আগের', { role: 'tab' }).catch(() => tap(page, 'আগের'));
    await J.shot('অর্ডার ‘আগের’ তালিকায়', 'রহিমের অর্ডার চালু তালিকা থেকে সরে ‘আগের’-এ, অবস্থা ‘দেওয়া হয়েছে’; ঠিকানা/ফোন আর দেখায় না।');
  });

  await J.sc('J-10', 'রোগী: কেনা ওষুধ দেখা', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await J.shot('রোগী: ওষুধ কেনা — ৬০/৬০ আর ৯/৯', '‘ওষুধ কেনা’: Comet ‘কেনা ৬০/৬০’, Napa ‘কেনা ৯/৯’; অর্ডার ‘দেওয়া হয়েছে’।', { full: true });
  });

  await J.sc('J-11', 'রোগী: পরীক্ষা ডেমো সিটি ডায়াগনস্টিকে পাঠানো', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await tap(page, 'পরীক্ষা করাতে পাঠান', { role: 'button' });
    await pickAreaIfAsked(page);
    await see(page, 'ডেমো সিটি ডায়াগনস্টিক সেন্টার');
    await J.shot('প্যাথলজি বাছাই', 'CBC আর HbA1c টিক দেওয়া; কাছের প্যাথলজি — ‘ডেমো সিটি ডায়াগনস্টিক সেন্টার’ (২টার ২টা পরীক্ষা, আনুমানিক দাম, বাসা থেকে নমুনা)।');
    const dlg = page.getByRole('dialog').last();
    await dlg.locator('article, .card').filter({ hasText: 'ডেমো সিটি ডায়াগনস্টিক সেন্টার' }).getByRole('button', { name: 'পরীক্ষা পাঠান' }).first().click();
    const h = page.getByRole('dialog').last();
    await h.getByText('বাসা থেকে নমুনা নিতে আসবে').click();
    await h.getByLabel('বাসার ঠিকানা').fill('ডেমো বাসা ৫, চরপাড়া');
    const ph = h.getByLabel('মোবাইল নম্বর');
    if (!(await ph.inputValue())) await ph.fill('01700001101');
    // কাল (আজ সময় না-ও থাকতে পারে)
    await h.locator('.chips, [role=radiogroup], .row').filter({ hasText: /কাল/ }).getByText(/^কাল$|আগামীকাল/).first().click().catch(() => {});
    await J.shot('বাসা থেকে নমুনা — ঠিকানা, ফোন, দিন, সময়', '‘বাসা থেকে নমুনা নিতে আসবে’ বাছা, ঠিকানা ‘ডেমো বাসা ৫, চরপাড়া’, মোবাইল, কাল, একটা সময়।');
    await h.getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'ডেমো সিটি ডায়াগনস্টিক সেন্টার-এ পাঠানো হয়েছে');
    await J.shot('ল্যাবে পাঠানো হলো', '‘ডেমো সিটি ডায়াগনস্টিক সেন্টার-এ পাঠানো হয়েছে’; অবস্থা ‘পাঠানো হয়েছে — অপেক্ষায়’।', { full: true });
  });

  await J.sc('J-12', 'প্যাথলজি: নমুনা নেওয়া আর রিপোর্ট লেখা', async () => {
    page = await J.as('lab');
    await labOrders(page);
    await J.shot('প্যাথলজি: পরীক্ষার অনুরোধ', '‘ডেমো রোগী রহিম’ (CBC, HbA1c) সারি, ‘নমুনা নিয়েছি’ বোতাম।', { full: true });
    let row = page.locator('tr, article').filter({ hasText: ME }).filter({ hasText: 'HbA1c' }).first();
    // বাসা থেকে নমুনা: আগে সময় নিশ্চিত (খুলে)
    await row.getByText(ME).click();
    await settle(page);
    await J.shot('অনুরোধ খোলা — বাসা থেকে নমুনা', 'বাসা থেকে নমুনা: ঠিকানা, ফোন, চাওয়া দিন-সময়; ‘সময় নিশ্চিত করুন’, ‘বাসায় যেতে পারব না’, দামের তালিকা।', { full: true });
    const conf = page.getByRole('button', { name: 'সময় নিশ্চিত করুন' }).first();
    if (await conf.count()) {
      await conf.click();
      const d = page.getByRole('dialog').last();
      await d.getByLabel(/রোগীর জন্য নোট/).fill('খালি পেটে থাকবেন').catch(() => {});
      await J.shot('বাসায় যাওয়ার সময় নিশ্চিত করা', 'দিন আর সময় বাছা, রোগীর জন্য নোট।');
      await d.getByRole('button', { name: /রোগীকে জানান|নিশ্চিত/ }).last().click();
      await see(page, 'রোগীকে সময় জানানো হয়েছে').catch(() => {});
      await J.shot('সময় নিশ্চিত হলো', '‘রোগীকে সময় জানানো হয়েছে’; অবস্থা ‘সময় নিশ্চিত’।');
    }
    await page.getByRole('button', { name: 'নমুনা নিয়েছি' }).first().click();
    await settle(page, 500);
    await J.shot('নমুনা নেওয়া হলো', 'সারির অবস্থা ‘নমুনা নেওয়া হয়েছে’, এখন ‘রিপোর্ট দিন’।');
    // খোলা অনুরোধে রিপোর্টের ঘর নিচেই; তালিকায় থাকলে ‘রিপোর্ট দিন’
    const rb = page.getByRole('button', { name: 'রিপোর্ট দিন' });
    if (await rb.count()) await rb.first().click();
    await settle(page);
    await page.getByLabel(/^HbA1c.*: ফল$/).first().scrollIntoViewIfNeeded();
    await J.shot('রিপোর্টের ঘর — একক আর স্বাভাবিক মাত্রা ভরা', 'CBC আর HbA1c-এর সারি, একক আর স্বাভাবিক মাত্রা আগে থেকে বসানো; শুধু ফল লিখতে হবে।', { full: true });
    await page.getByLabel(/^Haemoglobin.*: ফল$/).first().fill('13.2');
    await page.getByLabel(/^Total WBC count.*: ফল$/).first().fill('7.5');
    await page.getByLabel(/^Platelet count.*: ফল$/).first().fill('250');
    await page.getByLabel(/^HbA1c.*: ফল$/).first().fill('7.9');
    await page.keyboard.press('Tab');
    await J.shot('ফল লেখা — HbA1c-এর পাশে ‘বেশি’', 'Haemoglobin 13.2, WBC 7.5, Platelet 250, HbA1c 7.9 — HbA1c-এর পাশে নিজে থেকে ‘বেশি’।', { full: true });
    await page.getByRole('button', { name: 'রিপোর্ট পাঠান' }).click();
    const dlg = page.getByRole('dialog').last();
    await dlg.getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'রোগীর কাছে রিপোর্ট গেছে');
    await J.shot('রিপোর্ট পাঠানো হলো', '‘রোগীর কাছে রিপোর্ট গেছে’; সারি ‘রিপোর্ট দেওয়া হয়েছে’ ফিল্টারে।');
  });

  await J.sc('J-13', 'রোগী: রিপোর্ট দেখা (আগের ফল পাশে)', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await J.shot('প্রেসক্রিপশনে ‘রিপোর্ট এসেছে’', '‘পরীক্ষা ও রিপোর্ট’-এ ডেমো সিটি ডায়াগনস্টিক — ‘রিপোর্ট এসেছে’, ‘রিপোর্ট দেখুন’; নিচে ‘রিপোর্ট দেখানো’: ফি ৩০০ টাকা, 01700000202, ‘টাকা পাঠিয়েছি’।', { full: true });
    await tap(page, 'রিপোর্ট দেখুন');
    await J.shot('রিপোর্ট — আগের ফল পাশে', 'ফলের ছকে HbA1c 7.9 ‘বেশি’, ‘আগে’ ঘরে আড়াই মাস আগের 8.4।', { full: true });
    await page.keyboard.press('Escape');
  });

  await J.sc('J-14', 'রোগী: ডাক্তারকে রিপোর্ট দেখানোর টাকা (TrxID)', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await tap(page, 'টাকা পাঠিয়েছি', { role: 'button' });
    const dlg = page.getByRole('dialog').last();
    await dlg.getByRole('textbox').first().fill('DEMO1234AB');
    await J.shot('TrxID লেখা', 'TrxID ঘরে DEMO1234AB।');
    await dlg.getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'ডাক্তার টাকা মিলিয়ে দেখবেন');
    await J.shot('টাকা পাঠানোর খবর গেল', '‘পাঠানো হয়েছে। ডাক্তার টাকা মিলিয়ে দেখবেন।’; অবস্থা ‘টাকা পাঠিয়েছেন — ডাক্তার মিলিয়ে দেখছেন’।', { full: true });
  });

  await J.sc('J-16a', 'ডাক্তার: টাকা মেলার আগে রিপোর্ট খোলে না', async () => {
    page = await J.as('doctor');
    await openRx(page);
    await tap(page, 'রিপোর্ট', { role: 'tab' });
    await J.shot('ডাক্তার: রিপোর্ট — টাকা মেলাতে হবে', 'রহিমের রিপোর্ট ‘টাকা মেলাতে হবে’-তে — TrxID DEMO1234AB; ফল এখনো দেখা যায় না।', { full: true });
  });

  await J.sc('J-15', 'সহকারী: টাকা মেলানো', async () => {
    page = await J.as('assistant');
    await openRx(page);
    await tap(page, 'টাকা মেলানো', { role: 'tab' });
    await J.shot('সহকারী: টাকা মেলানো', '‘ডেমো রোগী রহিম’ (CBC, HbA1c · TrxID DEMO1234AB) সারি, ‘টাকা পেয়েছি’ আর ‘মেলেনি’।', { full: true });
    const row = page.locator('tr, article, .card').filter({ hasText: 'DEMO1234AB' }).first();
    await row.getByRole('button', { name: 'টাকা পেয়েছি' }).click();
    await see(page, /নিশ্চিত হয়েছে|রোগীকে জানানো হয়েছে/);
    await J.shot('টাকা মিলেছে', '‘নিশ্চিত হয়েছে’ / ‘রোগীকে জানানো হয়েছে’; সারি তালিকা থেকে সরে যায়।');
  });

  await J.sc('J-16', 'ডাক্তার: রোগীর ল্যাব রিপোর্ট পড়া আর জানানো', async () => {
    page = await J.as('doctor');
    await openRx(page);
    await tap(page, 'রিপোর্ট', { role: 'tab' });
    await tap(page, 'দেখতে হবে', { role: 'tab' }).catch(() => tap(page, 'দেখতে হবে'));
    await J.shot('ডাক্তার: রিপোর্ট — দেখতে হবে', 'রহিম (ডেমো সিটি ডায়াগনস্টিক সেন্টার · CBC, HbA1c) — ‘রিপোর্ট দেখে জানান’।');
    const row = page.locator('tr, article, .card').filter({ hasText: ME }).filter({ hasText: 'HbA1c' }).first();
    await row.getByRole('button', { name: 'রিপোর্ট দেখে জানান' }).click();
    const dlg = page.getByRole('dialog').last();
    await dlg.waitFor();
    await J.shot('পুরো রিপোর্ট — আগের HbA1c পাশে', 'প্রতিটা ফল, স্বাভাবিক মাত্রা, বেশি/কম, আর ‘আগে’ ঘরে HbA1c 8.4।');
    await dlg.getByLabel('রোগীকে কী জানাবেন?').fill('HbA1c একটু বেশি, ওষুধ চলবে। ১৫ দিন পর আসবেন।');
    await dlg.getByRole('button', { name: 'রোগীকে পাঠান' }).click();
    await settle(page, 500);
    await J.shot('রোগীকে মন্তব্য পাঠানো হলো', 'সারি ‘দেখা হয়েছে’-তে যায়।');
  });

  await J.sc('J-17', 'রোগী: ডাক্তারের মন্তব্য পাওয়া', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await J.shot('রোগী: ডাক্তার দেখেছেন', 'অবস্থা ‘ডাক্তার দেখেছেন’; ‘ডাক্তারের নোট: HbA1c একটু বেশি, ওষুধ চলবে। ১৫ দিন পর আসবেন।’', { full: true });
  });

  await J.sc('J-18', 'ডাক্তার: ফলো-আপে রোগীর আগের প্রেসক্রিপশন আর ল্যাবের রিপোর্ট', async () => {
    page = await J.as('doctor');
    await openRx(page);
    const row = page.locator('tr').filter({ hasText: ME }).filter({ hasText: 'সিরিয়াল ৫' }).first();
    await row.getByText(ME).click();
    await settle(page);
    await J.shot('রহিমের ইতিহাস — আগের প্রেসক্রিপশন আর পরীক্ষার রিপোর্ট', 'আজকেরটা (রিপোর্ট এসেছে, HbA1c 7.9 বেশি, রোগীকে জানানো মন্তব্য), DEMXRXB333 (টাকা মেলানো বাকি), DEMXRXC444 ‘বাতিল’, DEMXRXA222।', { full: true });
    const rep = page.getByRole('button', { name: 'রিপোর্ট দেখুন' }).first();
    if (await rep.count()) {
      await rep.click();
      await settle(page);
      await J.shot('ইতিহাস থেকে রিপোর্ট খোলা', 'পুরো রিপোর্ট, ‘আগে’ ঘরে 8.4।');
      await page.keyboard.press('Escape');
    }
    await go(page, '/work/rx');
    await tap(page, 'রোগী', { role: 'tab', exact: true });
    await page.locator('tr').filter({ hasText: 'ডেমো রোগী আব্দুল করিম' }).first().getByText('ডেমো রোগী আব্দুল করিম').click();
    await settle(page);
    await J.shot('আব্দুল করিমের ইতিহাস', '১০ দিন আগের প্রেসক্রিপশনে CBC, RBS ‘রিপোর্ট এসেছে’ — ফল নয়: ‘রোগী রিপোর্ট দেখানোর অনুরোধ করলে দেখা যাবে’।', { full: true });
  });

  await J.sc('J-19', 'রোগী: আবার দেখানোর সিরিয়াল', async () => {
    page = await J.as('patient');
    await openMyRx(page);
    await tap(page, 'সিরিয়াল নিন', { role: 'button' });
    await see(page, 'অনুরোধ গেছে');
    await J.shot('আবার দেখানোর সিরিয়াল চাওয়া', '‘অনুরোধ গেছে। ডাক্তার নিশ্চিত করলে নোটিফিকেশন পাবেন।’');
    await go(page, '/my-appointments');
    await J.shot('আমার অ্যাপয়েন্টমেন্ট — ১৫ দিন পরের তারিখে অপেক্ষায়', '‘ডা. ডেমো হাসান’ — ১৫ দিন পরের তারিখ, ‘অপেক্ষায়’।');
  });

  await J.sc('J-20', 'ডাক্তার: আজকের সিরিয়াল চালানো — পরের রোগী', async () => {
    page = await J.as('doctor');
    await chamberToday(page);
    const qc = page.locator('section, .card').filter({ hasText: 'আজকের সিরিয়াল' }).filter({ has: page.getByRole('button', { name: 'পরের রোগী' }) }).first();
    await J.shot('আজকের সিরিয়াল — চলছে, এখন দেখছি ২', '‘চলছে’, ‘এখন দেখছি’ ২, ‘পরের’ ৩, ‘গড়’ ৮ মি.; সারিতে ১–৫ (রহিম ৫)।');
    await qc.getByRole('button', { name: 'পরের রোগী' }).click();
    await settle(page, 600);
    await J.shot('পরের রোগী চাপার পর — এখন দেখছি ৩', '‘এখন দেখছি’ ৩ (রাশেদুল ইসলাম, সবুজ সারি), ‘পরের’ ৪; ২ নম্বরের পাশে ‘দেখা হয়েছে’।');
  });

  await J.sc('J-21', 'রোগী: এখন কত নম্বর চলছে আর কখন যাবেন', async () => {
    page = await J.as('patient');
    await go(page, '/inbox');
    await J.shot('রোগীর নোটিফিকেশন', '‘আপনার আগে ২ জন — চেম্বারে চলে আসুন’ নোটিফিকেশন (একবারই); তারিখ আর সিরিয়াল বাংলা অঙ্কে (‘১৯/১০/২০২৬ · #৫’)।', { status: 'fixed', noteBn: 'আগে বাংলা ভাষাতেও নোটিফিকেশনের নিচের লাইনে তারিখ আর সিরিয়াল ইংরেজি অঙ্কে আসত (‘19/10/2026 · #5’)। এখন বাংলায় বাংলা অঙ্ক; ওষুধ/পরীক্ষার নামের ভেতরের অঙ্ক (HbA1c) বদলায় না। (সার্ভারের পরীক্ষা যোগ হয়েছে)' });
    await go(page, '/my-appointments');
    await J.shot('তালিকার কার্ডে এক লাইনে সারির খবর', '‘এখন চলছে: ৩ · আপনার আগে ২ জন · …’।');
    await openMyAppt(page);
    await J.shot('আজকের সিরিয়াল বাক্স', '‘এখন চলছে’ ৩, ‘আপনার সিরিয়াল’ ৫, ‘আপনার আগে ২ জন’, ‘ডাক্তার গড়ে ৮ মিনিট নেন’, নীল ঘরে আনুমানিক সময়।');
  });

  await J.sc('J-22', 'রোগী: দেরি হবে — সময় চাওয়া', async () => {
    page = await J.as('patient');
    await openMyAppt(page);
    await tap(page, 'দেরি হবে', { role: 'button' });
    const dlg = page.getByRole('dialog').last();
    await dlg.getByText('২০ মিনিট', { exact: true }).click();
    await dlg.getByLabel('নোট (ঐচ্ছিক)').fill('রিকশা পাচ্ছি না');
    await J.shot('দেরি হবে — ২০ মিনিট, নোট', '২০ মিনিট বাছা, নোট ‘রিকশা পাচ্ছি না’।');
    await dlg.getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'উত্তরের অপেক্ষায়');
    await J.shot('সময় চাওয়া হয়েছে — উত্তরের অপেক্ষায়', '‘ডাক্তারকে পাঠানো হয়েছে’; বাক্সে ‘সময় চেয়েছেন: ২০ মিনিট · উত্তরের অপেক্ষায়’।');
  });

  await J.sc('J-23', 'ডাক্তার: সময় চাওয়ার উত্তর — পরে আসুন', async () => {
    page = await J.as('doctor');
    await go(page, '/inbox');
    await J.shot('ডাক্তারের নোটিফিকেশন', '‘রোগী ২০ মিনিট দেরিতে আসবেন’।');
    await chamberToday(page);
    const row = page.locator('.appt-q-row, li, div[role=button], button').filter({ hasText: ME }).filter({ hasText: '২০ মিনিট দেরি হবে' }).first();
    await J.shot('রহিমের সারিতে ‘২০ মিনিট দেরি হবে’', 'লাল ‘২০ মিনিট দেরি হবে’, নিচে ‘রিকশা পাচ্ছি না’, পাশে ‘ঠিক আছে’ আর ‘পরে আসুন’।');
    const box = page.locator('section, .card').filter({ hasText: 'আজকের সিরিয়াল' }).first();
    const later = box.locator('div').filter({ hasText: 'রিকশা পাচ্ছি না' }).getByRole('button', { name: 'পরে আসুন' }).last();
    await later.click();
    await settle(page, 600);
    await J.shot('পরে আসুন — রহিম ‘শেষে’', 'রহিমের সারিতে ‘শেষে’; অন্য কারো নম্বর বদলায় না।');
    void row;
  });

  await J.sc('J-24', 'রোগী: ডাক্তারের উত্তর দেখা', async () => {
    page = await J.as('patient');
    await go(page, '/inbox');
    await J.shot('রোগীর নোটিফিকেশন — পরে আসুন', '‘ডাক্তার: পরে আসুন — আপনাকে সবার শেষে দেখা হবে’।');
    await openMyAppt(page);
    await J.shot('বাক্সে ‘আপনাকে সবার শেষে দেখা হবে’', '‘আপনাকে সবার শেষে দেখা হবে’ আর ‘সময় চেয়েছেন: ২০ মিনিট · ডাক্তার: পরে আসুন’; ‘দেরি হবে’ বোতাম নেই।');
    if (await page.getByRole('button', { name: 'দেরি হবে' }).count()) throw new Error('‘পরে আসুন’-এর পরেও ‘দেরি হবে’ বোতাম আছে');
  });

  // R-35 আর R-34 (rx.json) — গাইড বলে J-25-এর আগে করতে (মরিয়মের দেরি আর বিরতি)
  await J.sc('R-35', 'সহকারী: দেরির অনুরোধে ‘ঠিক আছে’', async () => {
    page = await J.as('assistant');
    await openRx(page);
    const box = page.locator('section, .card').filter({ hasText: 'আজকের সিরিয়াল' }).first();
    await J.shot('সহকারী: মরিয়মের ‘২০ মিনিট দেরি হবে’', 'লাল ‘২০ মিনিট দেরি হবে’, ‘জ্যামে আটকে আছি’, ‘ঠিক আছে’ / ‘পরে আসুন’।');
    await box.locator('div').filter({ hasText: 'জ্যামে আটকে আছি' }).getByRole('button', { name: 'ঠিক আছে' }).last().click();
    await settle(page, 600);
    await J.shot('ঠিক আছে — সিরিয়াল ৪-ই থাকে', 'বোতাম চলে যায়, ‘২০ মিনিট দেরি হবে’ (নীল) থাকে — ক্রম বদলায় না।');
  });

  await J.sc('R-34', 'সহকারী: সিরিয়াল চালানো আর বিরতি', async () => {
    const box = page.locator('section, .card').filter({ hasText: 'আজকের সিরিয়াল' }).filter({ has: page.getByRole('button', { name: 'বিরতি' }) }).first();
    await box.getByRole('button', { name: 'বিরতি' }).click();
    await settle(page, 600);
    await J.shot('বিরতি', 'লেবেল ‘বিরতি’, বোতাম ‘আবার শুরু’।');
    page = await J.as('patient');
    await openMyAppt(page);
    await J.shot('রোগী: ডাক্তার একটু বিরতিতে', 'রোগীর বাক্সে ‘ডাক্তার একটু বিরতিতে’।');
    page = await J.as('assistant');
    await openRx(page);
    await page.getByRole('button', { name: 'আবার শুরু' }).first().click();
    await settle(page, 600);
    await J.shot('আবার শুরু', 'আবার ‘চলছে’।');
  });

  await J.sc('J-25', 'ডাক্তার: পরের রোগী চাপতে চাপতে রহিমের পালা', async () => {
    page = await J.as('doctor');
    await chamberToday(page);
    const qc = () => page.locator('section, .card').filter({ hasText: 'আজকের সিরিয়াল' }).filter({ has: page.getByRole('button', { name: 'পরের রোগী' }) }).first();
    await qc().getByRole('button', { name: 'পরের রোগী' }).click();
    await settle(page, 600);
    await J.shot('এখন দেখছি ৪ (মরিয়ম নেসা)', '‘এখন দেখছি’ ৪।');
    await qc().getByRole('button', { name: 'পরের রোগী' }).click();
    await settle(page, 600);
    await J.shot('এখন দেখছি ৫ — শেষে রাখা রহিম', '৪-এর পর সবার শেষে রাখা রহিম (৫) — ‘এখন দেখছি’ ৫।');
    page = await J.as('patient');
    await openMyAppt(page);
    await J.shot('রোগী: এখন আপনার পালা', 'রোগীর বাক্সে ‘এখন আপনার পালা’।');
    await see(page, 'এখন আপনার পালা');
    page = await J.as('doctor');
    await chamberToday(page);
    await qc().getByRole('button', { name: 'পরের রোগী' }).click();
    await settle(page, 600);
    await J.shot('আরেকবার পরের রোগী — সিরিয়াল শেষ', 'বাকি কেউ না থাকায় লেবেল ‘শেষ’।');
    page = await J.as('patient');
    await openMyAppt(page);
    await J.shot('রোগী: দেখা হয়ে গেছে', 'রোগীর বাক্সে ‘দেখা হয়ে গেছে’।');
  });

  await J.sc('R-36', 'সিরিয়াল চেপে ‘এখন দেখছি’', async () => {
    page = await J.as('doctor');
    await chamberToday(page);
    const box = page.locator('section, .card').filter({ hasText: 'আজকের সিরিয়াল' }).first();
    await box.getByRole('button', { name: /^১ ডেমো রোগী সালমা খাতুন/ }).click();
    await settle(page, 600);
    await J.shot('সালমার সারি চেপে এখন দেখছি ১', '‘এখন দেখছি: ১’; সালমার সারি সবুজ।');
    const next = box.getByRole('button', { name: 'পরের রোগী' });
    if (await next.isEnabled()) {
      await next.click();
      await settle(page, 600);
    }
    await J.shot('পরের রোগী — পেছনে ফিরে সবাইকে ডাকে না', 'আগের জায়গার পরে যায় — বাকি না থাকায় ‘শেষ’।');
  });
}

/** এলাকা চাইলে ময়মনসিংহ › ময়মনসিংহ সদর */
async function pickAreaIfAsked(page) {
  await settle(page);
  const choose = page.getByRole('dialog').last().getByText('এলাকা বেছে নিন');
  if (await choose.count()) {
    await page.getByRole('dialog').last().getByRole('button', { name: /এলাকা/ }).first().click();
    await tap(page, 'ময়মনসিংহ', { exact: true });
    await tap(page, 'ময়মনসিংহ সদর', { exact: true }).catch(() => {});
    await settle(page);
  }
}

/** আমার কাজ › প্যাথলজি › পরীক্ষা ও রিপোর্ট › পরীক্ষার অনুরোধ */
async function labOrders(page) {
  await go(page, '/work/lab');
  await page.locator('.card, section, article').filter({ hasText: /পরীক্ষা ও রিপোর্ট|প্রেসক্রিপশন/ }).getByRole('link', { name: 'খুলুন' }).first().click();
  await settle(page);
  const t = page.getByRole('tab', { name: /পরীক্ষার অনুরোধ/ });
  if (await t.count()) await t.first().click();
  await settle(page);
}

/** আমার কাজ › ফার্মেসি › প্রেসক্রিপশন ও অর্ডার (খুলুন) › ট্যাব */
async function pharmacyOrders(page, tab) {
  await go(page, '/work/pharmacy');
  await page.locator('.card, section, article').filter({ hasText: 'প্রেসক্রিপশন' }).getByRole('link', { name: 'খুলুন' }).first().click();
  await settle(page);
  if (tab) await tap(page, tab, { role: 'tab' });
}

/** আমার কাজ › প্রেসক্রিপশন › খুলুন (রোগী ট্যাব) */
async function openRx(page) {
  await go(page, '/work');
  const card = page.locator('a, .card').filter({ hasText: 'প্রেসক্রিপশন' }).filter({ hasText: /রোগী|সহকারী/ });
  await card.first().click();
  await settle(page);
  const t = page.getByRole('tab', { name: 'রোগী', exact: true });
  if (await t.count()) await t.click();
  await settle(page);
}

/** এক ওষুধের সারি ভরা (ওষুধের নাম লিখে তালিকা বন্ধ করে) */
async function fillMed(page, i, m) {
  const row = page.locator('.rxd-med[data-med]').nth(i);
  const name = row.getByRole('combobox').or(row.locator('.rxd-c-name input')).first();
  await name.fill(m.name);
  await page.waitForTimeout(400);
  await name.press('Escape');
  await row.locator('[data-f=strength]').fill(m.strength);
  await row.locator('[data-f=dose]').fill(m.dose);
  await row.locator('[data-f=timing]').selectOption('after');
  await row.locator('[data-f=days]').fill(m.days);
}

/** প্রোফাইল › আমার প্রেসক্রিপশন › সবার ওপরের ডা. ডেমো হাসান */
async function openMyRx(page) {
  await go(page, '/profile');
  await tap(page, 'আমার প্রেসক্রিপশন');
  await page.locator('article, [role=button]').filter({ hasText: DOC }).first().click();
  await settle(page);
}
