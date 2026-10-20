// সাহায্য ও আপিল, অ্যাডমিন যোগ/বাদ, অ্যাকাউন্ট বন্ধ/চালু, নতুন ব্যবহারকারীর ইমেইল-লগইন, জরুরি রক্তের আহ্বান — ওয়েব (Vue) আর
// অ্যাডমিন প্যানেল (Vue, কড়া CSP) দিয়ে আসল সার্ভারে:
//   ticket_create, tickets_mine, admin_tickets, admin_ticket, admin_ticket_reply, ticket_reply, inbox;
//   admin_list (overview), admin_add, admin_remove (+ নতুন অ্যাডমিনের নিজের লগইন);
//   admin_user_find, admin_user, admin_user_action (disable/enable) — বন্ধ অ্যাকাউন্টে লগইন আটকায়;
//   /api/auth/send-code + verify-code (নতুন ইমেইল), profile_update (নাম-ফোন, পরে রক্তদাতার তথ্য);
//   admin_districts, admin_hospitals, admin_emergency_create, emergencies_active, emergency_respond, admin_emergencies,
//   admin_emergency_group (বন্ধ/খোলা), admin_emergency_more, admin_emergency_close।
// জরুরি আহ্বান আসল দুনিয়ার — ডেমো অ্যাকাউন্টে কখনো যায় না (সার্ভারের নিয়ম), তাই এখানে একজন সত্যিকারের নতুন ব্যবহারকারী ইমেইলে
// লগইন করে (গাজীপুর, O-) আহ্বানে সাড়া দেন।
import { tap, see, go, settle, logout, until } from '../lib.mjs';
import { BASE } from '../stack.mjs';
import { adminLogin, OWNER_EMAIL } from './00-seed.mjs';
import { pickDistrict } from './02-blood.mjs';

export const meta = { no: 10, slug: 'admin-support', titleBn: 'আপিল, অ্যাডমিন, অ্যাকাউন্ট বন্ধ, নতুন ব্যবহারকারী, জরুরি আহ্বান' };

const NEW_ADMIN = 'e2e-admin@lifolo-e2e.test';
const NEW_USER = 'e2e-donor@lifolo-e2e.test';
const TICKET = 'ডেমো আপিল: আমার রক্তদানের তারিখ ভুল দেখাচ্ছে, ঠিক করে দিন।';
const HOSPITAL = 'ই২ই জরুরি হাসপাতাল, জয়দেবপুর';

/** ডেস্কটপের পাতায় মালিক হিসেবে অ্যাডমিন প্যানেল (ওয়েবের রোল থেকে লগআউট করে) */
async function owner(J, hash = '') {
  const P = await J.ctxFor('desktop');
  J.cur = P;
  if (P.role !== 'owner') {
    if (P.role && P.role !== 'guest') await logout(P.page).catch(() => {});
    await adminLogin(P.page);
    P.role = 'owner';
  }
  await P.page.goto(`${BASE}/admin${hash}`);
  await settle(P.page, 500);
  return P.page;
}

/** অ্যাডমিন প্যানেলের বোতাম — সতর্কতার বোতামে দ্বিতীয় চাপ ("নিশ্চিত? আবার চাপুন"), তারপর সবুজ বার্তা */
async function adminDo(page, scope, name, done) {
  await scope.getByRole('button', { name, exact: true }).first().click();
  await page.waitForTimeout(200);
  const armed = page.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' });
  if (await armed.count()) await armed.first().click();
  if (done) await page.getByText(done).first().waitFor({ timeout: 15000 });
  await settle(page, 500);
}

/** নতুন ব্রাউজার-জানালায় (আলাদা কুকি) অ্যাডমিন প্যানেলে কোনো ইমেইলে লগইনের চেষ্টা */
async function freshAdminLogin(J, email) {
  const ctx = await J.ctxFor('desktop').then((P) => P.ctx.browser().newContext({ viewport: { width: 1366, height: 900 }, locale: 'bn-BD', timezoneId: 'Asia/Dhaka' }));
  const page = await ctx.newPage();
  page.setDefaultTimeout(8000);
  await page.goto(`${BASE}/admin`);
  await settle(page, 400);
  await page.locator('input[type=email]').fill(email);
  await page.getByRole('button').filter({ hasText: /কোড/ }).first().click();
  return { ctx, page };
}

export async function run(J) {
  let page;
  let ticketUrl;

  await J.sc('ADM-01', 'রোগী: সাহায্য ও আপিল — "আমাদের লিখুন"', async () => {
    page = await J.as('patient');
    await go(page, '/help');
    await J.shot('সাহায্য ও আপিল', '‘আমাদের লিখুন’ আর ‘আমার অনুরোধ’ (আগের অনুরোধ থাকলে তালিকা)।');
    await tap(page, 'আমাদের লিখুন', { role: 'button' });
    await page.getByRole('button', { name: 'রক্তের অনুরোধ', exact: true }).click();
    await page.getByLabel('সমস্যাটা খুলে লিখুন').fill(TICKET);
    await J.shot('নতুন আপিল — বিষয় আর লেখা', '‘কী নিয়ে?’ — ‘রক্তের অনুরোধ’ বাছা, লেখা ভরা, ‘পাঠান’।');
    await page.getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'পাঠানো হয়েছে। Lifolo টিম এখানেই উত্তর দেবে।');
    await page.waitForURL(/\/help\/[^/]+$/);
    ticketUrl = new URL(page.url()).pathname;
    await see(page, TICKET);
    await J.shot('আপিল পাঠানো হলো', '‘পাঠানো হয়েছে। Lifolo টিম এখানেই উত্তর দেবে।’ — কথোপকথনে নিজের লেখা, অবস্থা ‘উত্তরের অপেক্ষায়’।', { full: true });
  });

  await J.sc('ADM-02', 'অ্যাডমিন: আপিলের তালিকা, খোলা, উত্তর', async () => {
    page = await owner(J, '#/tickets');
    const row = page.locator('tr').filter({ hasText: TICKET.slice(0, 30) }).first();
    await row.waitFor({ timeout: 15000 });
    await J.shot('অ্যাডমিন: আপিল ও সমস্যা — উত্তরের অপেক্ষায়', `তালিকায় ‘ডেমো রোগী রহিম’ — বিষয় ‘রক্তের অনুরোধ’, শেষ বার্তা ‘${TICKET.slice(0, 30)}…’।`);
    await row.click();
    await page.getByRole('textbox', { name: 'উত্তর' }).waitFor();
    await page.getByRole('textbox', { name: 'উত্তর' }).fill('ডেমো উত্তর: আপনার শেষ রক্তদানের তারিখ দেখছি, প্রোফাইল থেকে সংশোধনের অনুরোধ দিন।');
    await J.shot('অ্যাডমিন: আপিল — উত্তর লেখা', 'কথোপকথন, উত্তরের ঘর ভরা, ‘উত্তর পাঠান’ / ‘উত্তর দিয়ে বন্ধ করুন’ / ‘উত্তর ছাড়া বন্ধ করুন’।');
    await adminDo(page, page, 'উত্তর পাঠান', 'উত্তর পাঠানো হয়েছে');
    await J.shot('অ্যাডমিন: উত্তর পাঠানো', '‘উত্তর পাঠানো হয়েছে’।');
  });

  await J.sc('ADM-03', 'রোগী: উত্তর দেখা (ঘণ্টাতেও) আর আবার লেখা', async () => {
    page = await J.as('patient');
    await go(page, ticketUrl);
    await see(page, 'ডেমো উত্তর: আপনার শেষ রক্তদানের তারিখ দেখছি');
    await J.shot('রোগী: Lifolo টিমের উত্তর', 'কথোপকথনে Lifolo টিমের উত্তর; অবস্থা ‘উত্তর দেওয়া হয়েছে’।', { full: true });
    await go(page, '/inbox');
    await see(page, 'Lifolo টিমের উত্তর এসেছে');
    await J.shot('ঘণ্টা: Lifolo টিমের উত্তর এসেছে', 'নোটিফিকেশনের তালিকায় ‘Lifolo টিমের উত্তর এসেছে’।');
    await page.getByText('Lifolo টিমের উত্তর এসেছে').first().click();
    await page.waitForURL(/\/help/, { timeout: 10000 });
    await go(page, ticketUrl);
    await page.getByLabel('আরও লিখুন').fill('ধন্যবাদ, প্রোফাইল থেকে অনুরোধ দিয়েছি।');
    await page.getByRole('button', { name: 'পাঠান', exact: true }).click();
    await see(page, 'ধন্যবাদ, প্রোফাইল থেকে অনুরোধ দিয়েছি।');
    await J.shot('রোগী: আবার লেখা', 'নিজের নতুন লেখা কথোপকথনে (ticket_reply) — আপিল আবার ‘উত্তরের অপেক্ষায়’।', { full: true });
    page = await owner(J, '#/tickets');
    await page.locator('tr').filter({ hasText: 'ধন্যবাদ, প্রোফাইল থেকে অনুরোধ দিয়েছি।' }).first().waitFor({ timeout: 15000 });
    await J.shot('অ্যাডমিন: আপিল আবার খোলা', 'ব্যবহারকারীর নতুন লেখায় আপিল আবার ‘উত্তরের অপেক্ষায়’ তালিকায়।');
  });

  await J.sc('ADM-04', 'মালিক: নতুন অ্যাডমিন যোগ — তিনি প্যানেলে ঢোকেন; বাদ দিলে আর পারেন না', async () => {
    page = await owner(J, '#/admins');
    await page.getByRole('textbox', { name: 'নতুন অ্যাডমিনের ইমেইল' }).fill(NEW_ADMIN);
    await adminDo(page, page, 'যোগ করুন', 'অ্যাডমিন যোগ হয়েছে');
    await page.locator('tr').filter({ hasText: NEW_ADMIN }).first().waitFor({ timeout: 10000 });
    await J.shot('মালিক: অ্যাডমিন যোগ হলো', `তালিকায় মালিক (${OWNER_EMAIL}) আর নতুন ‘${NEW_ADMIN}’ — পাশে ‘বাদ দিন’।`);
    // নতুন অ্যাডমিন নিজের ইমেইলে প্যানেলে
    const a = await freshAdminLogin(J, NEW_ADMIN);
    const codeBox = a.page.locator('input[inputmode=numeric], input[autocomplete=one-time-code]').first();
    await codeBox.waitFor();
    const { code } = await (await fetch(`${BASE}/__e2e/mail?to=${NEW_ADMIN}`)).json();
    await codeBox.fill(code);
    await a.page.getByRole('button').filter({ hasText: /লগইন|যাচাই|ঢুকুন/ }).first().click();
    await settle(a.page, 600);
    await a.page.getByText('আপিল ও সমস্যা').first().waitFor({ timeout: 15000 });
    await J.shot('নতুন অ্যাডমিন প্যানেলে', 'নতুন অ্যাডমিন নিজের ইমেইলের কোডে ঢুকে বাম মেনু দেখেন (‘অ্যাডমিন’ মেনু নেই — ওটা শুধু মালিকের)।', { page: a.page });
    if (await a.page.locator('nav, aside').getByText('অ্যাডমিন', { exact: true }).count()) throw new Error('নতুন অ্যাডমিন "অ্যাডমিন" মেনু দেখছেন (শুধু মালিকের হওয়ার কথা)');
    // মালিক বাদ দেন
    page = await owner(J, '#/admins');
    const row = page.locator('tr').filter({ hasText: NEW_ADMIN }).first();
    await adminDo(page, row, 'বাদ দিন', 'অ্যাডমিন থেকে বাদ দেওয়া হয়েছে');
    await settle(page, 500);
    if (await page.locator('tr').filter({ hasText: NEW_ADMIN }).count()) throw new Error('বাদ দেওয়ার পরেও তালিকায়');
    await J.shot('মালিক: অ্যাডমিন বাদ', `‘${NEW_ADMIN}’ তালিকা থেকে চলে গেছে।`);
    // বাদ পড়া অ্যাডমিনের সেশন আর কাজ করে না (প্রতিটা অনুরোধে অ্যাডমিন কি না আবার দেখা হয়)
    await a.page.goto(`${BASE}/admin#/tickets`);
    await settle(a.page, 800);
    const ok = await a.page.locator('tr').filter({ hasText: 'ডেমো রোগী রহিম' }).count();
    await J.shot('বাদ পড়া অ্যাডমিন — প্যানেল বন্ধ', 'আপিলের তালিকা আর আসে না (লগইন পাতা বা ‘অনুমতি নেই’)।', { page: a.page });
    await a.ctx.close();
    if (ok) throw new Error('বাদ দেওয়ার পরেও আগের অ্যাডমিন আপিলের তালিকা দেখছেন');
  });

  await J.sc('ADM-05', 'অ্যাডমিন: ডেমো ডোনারের অ্যাকাউন্ট বন্ধ — লগইন আটকায়; আবার খুলে দেওয়া', async () => {
    page = await owner(J, '#/users');
    await page.getByRole('searchbox', { name: 'ব্যবহারকারী খুঁজুন' }).fill('donor@lifolo-demo.test');
    await page.getByRole('searchbox', { name: 'ব্যবহারকারী খুঁজুন' }).press('Enter');
    const row = page.locator('tr').filter({ hasText: 'donor@lifolo-demo.test' }).first();
    await row.waitFor({ timeout: 15000 });
    await row.click();
    await settle(page, 600);
    await page.getByRole('textbox', { name: 'কারণ' }).first().fill('ডেমো: ভুয়া রিকোয়েস্ট (পরীক্ষা)');
    await J.shot('অ্যাডমিন: ব্যবহারকারী — অ্যাকাউন্ট চালু', '‘অ্যাকাউন্ট — চালু’, কারণের ঘর ভরা, ‘অ্যাকাউন্ট বন্ধ করুন’।');
    await adminDo(page, page, 'অ্যাকাউন্ট বন্ধ করুন', 'অ্যাকাউন্ট বন্ধ করা হয়েছে');
    await page.getByRole('button', { name: 'অ্যাকাউন্ট খুলে দিন' }).waitFor({ timeout: 10000 });
    await J.shot('অ্যাকাউন্ট বন্ধ', '‘বন্ধ: লগইন করতে পারবেন না।’ আর ‘অ্যাকাউন্ট খুলে দিন’।');
    // ওয়েবে ডোনার হিসেবে ঢোকার চেষ্টা (নতুন জানালা, ডেমো তালিকা)
    const ctx = await page.context().browser().newContext({ viewport: { width: 412, height: 915 }, locale: 'bn-BD', timezoneId: 'Asia/Dhaka', isMobile: true, hasTouch: true });
    const p = await ctx.newPage();
    await p.goto(`${BASE}/login`);
    const dlg = p.getByRole('dialog');
    await dlg.getByText('ডেমো (পরীক্ষার জন্য)').waitFor();
    await dlg.getByText('রক্তদাতা', { exact: false }).first().click();
    await settle(p, 800);
    await p.getByText('আপনার অ্যাকাউন্ট Lifolo টিম বন্ধ করেছে।').first().waitFor({ timeout: 10000 });
    await see(p, 'কারণ: ডেমো: ভুয়া রিকোয়েস্ট (পরীক্ষা)');
    await J.shot('বন্ধ অ্যাকাউন্টে লগইন — আটকায়', '‘আপনার অ্যাকাউন্ট Lifolo টিম বন্ধ করেছে। কারণ: ডেমো: ভুয়া রিকোয়েস্ট (পরীক্ষা)। আপিল করতে ইমেইল করুন: …’', { page: p });
    await ctx.close();
    await adminDo(page, page, 'অ্যাকাউন্ট খুলে দিন', 'অ্যাকাউন্ট খুলে দেওয়া হয়েছে');
    await J.shot('অ্যাকাউন্ট আবার চালু', '‘অ্যাকাউন্ট খুলে দেওয়া হয়েছে’ — আবার ‘চালু’।');
    const d = await J.as('donor', 'phone');
    await go(d, '/profile');
    await see(d, 'ডেমো ডোনার করিম');
    await J.shot('ডোনার আবার লগইন করতে পারেন', 'খুলে দেওয়ার পর ডোনার আবার ঢোকেন — প্রোফাইলে ‘ডেমো ডোনার করিম’।', { page: d });
  });

  await J.sc('ADM-06', 'নতুন ব্যবহারকারী: ইমেইলে কোড দিয়ে লগইন, নাম-ফোন, এলাকা গাজীপুর', async () => {
    const ctx = await page.context().browser().newContext({ viewport: { width: 412, height: 915 }, locale: 'bn-BD', timezoneId: 'Asia/Dhaka', isMobile: true, hasTouch: true, geolocation: { latitude: 23.999, longitude: 90.42 }, permissions: ['geolocation'] });
    J.newUser = { ctx, page: await ctx.newPage() };
    const p = J.newUser.page;
    p.setDefaultTimeout(8000);
    await p.goto(`${BASE}/login`);
    const dlg = p.getByRole('dialog');
    await dlg.getByLabel('ইমেইল').fill(NEW_USER);
    await J.shot('লগইন — ইমেইল', '‘ইমেইলে একটা কোড যাবে, পাসওয়ার্ড লাগবে না।’ — ইমেইল ভরা, ‘কোড পাঠান’।', { page: p });
    await dlg.getByRole('button', { name: 'কোড পাঠান' }).click();
    await dlg.getByLabel('৬ সংখ্যার কোড').waitFor();
    const { code } = await (await fetch(`${BASE}/__e2e/mail?to=${NEW_USER}`)).json();
    if (!/^\d{6}$/.test(code || '')) throw new Error(`লগইন কোড পাওয়া যায়নি: ${code}`);
    await dlg.getByLabel('৬ সংখ্যার কোড').fill(code);
    await J.shot('লগইন — কোড', `${NEW_USER} — ‘বদলান’, ৬ সংখ্যার কোড ভরা।`, { page: p });
    await dlg.getByRole('button', { name: 'লগইন', exact: true }).click();
    await dlg.getByLabel('নাম').waitFor({ timeout: 15000 });
    await dlg.getByLabel('নাম').fill('ই২ই নতুন রক্তদাতা');
    await dlg.getByLabel('মোবাইল নম্বর').fill('01711000222');
    await J.shot('প্রথমবার — আপনার প্রোফাইল', 'নাম আর মোবাইল নম্বর, ‘এগিয়ে যান’।', { page: p });
    await dlg.getByRole('button', { name: 'এগিয়ে যান' }).click();
    await until(p, () => !document.querySelector('[role="dialog"]'), null, 15000);
    await settle(p);
    // এলাকা: হোমে "এলাকা বেছে নিন" (না জিজ্ঞেস করলে প্রোফাইল থেকে)
    await p.goto(`${BASE}/`);
    await settle(p);
    const choose = p.getByRole('button', { name: /এলাকা বেছে নিন|এলাকা বদলান|গাজীপুর/ }).first();
    if (!(await p.getByRole('dialog').count()) && (await choose.count())) await choose.click();
    if (await p.getByRole('dialog').count()) await pickDistrict(p);
    await go(p, '/profile');
    await see(p, 'ই২ই নতুন রক্তদাতা');
    await J.shot('নতুন ব্যবহারকারীর প্রোফাইল', 'নাম ‘ই২ই নতুন রক্তদাতা’, ইমেইল, এলাকা গাজীপুর; রক্তদাতার তথ্য এখনো নেই।', { page: p, full: true });
  });

  let emergencyRow;
  await J.sc('ADM-07', 'অ্যাডমিন: জরুরি রক্তের আহ্বান — গাজীপুর, O- ২ ব্যাগ', async () => {
    page = await owner(J, '#/emergency');
    await page.getByRole('combobox', { name: 'জেলা' }).selectOption('গাজীপুর');
    await settle(page, 600);
    await page.getByPlaceholder('তালিকায় না থাকলে নাম লিখুন').fill(HOSPITAL);
    await page.getByRole('spinbutton', { name: 'O- কত ব্যাগ' }).fill('2');
    await page.getByPlaceholder('01XXXXXXXXX').fill('01711000333');
    await page.getByPlaceholder(/বাস দুর্ঘটনা/).fill('ই২ই: বাস দুর্ঘটনা, জরুরি বিভাগের ব্লাড ব্যাংকে আসুন');
    await page.getByText('হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে নিশ্চিত হয়েছি যে এই রক্ত দরকার').click();
    await J.shot('অ্যাডমিন: নতুন জরুরি আহ্বান', `জেলা গাজীপুর, ‘${HOSPITAL}’, O- ২ ব্যাগ, ২৫ কিমি, ১২ ঘণ্টা, মোবাইল, বার্তা, নিশ্চিতের টিক।`, { full: true });
    await adminDo(page, page, 'আহ্বান পাঠান', 'আহ্বান চালু হয়েছে');
    emergencyRow = page.locator('.card').filter({ hasText: HOSPITAL }).first();
    await emergencyRow.waitFor({ timeout: 10000 });
    await J.shot('আহ্বান চালু', `‘চালু আহ্বান’-এ ${HOSPITAL} — O- ০/২; ‘আরও ডোনার জানান’, ‘আহ্বান শেষ করুন’।`, { full: true });
  });

  await J.sc('ADM-08', 'নতুন ব্যবহারকারী: হোমে জরুরি আহ্বান → "আসছি" → রক্তদাতার তথ্য → আসছেন', async () => {
    const p = J.newUser.page;
    await p.goto(`${BASE}/`);
    await settle(p, 600);
    const card = p.locator('section, article, div').filter({ hasText: 'জরুরি আহ্বান · Lifolo টিম' }).filter({ hasText: HOSPITAL }).last();
    await card.waitFor({ timeout: 15000 });
    await J.shot('হোম: জরুরি আহ্বান', `‘জরুরি আহ্বান · Lifolo টিম’ — ${HOSPITAL}, O- ০/২ আসছেন, ‘আসছি’, ‘হাসপাতালে ফোন’।`, { page: p, full: true });
    await card.getByRole('button', { name: 'আসছি' }).first().click();
    // রক্তদাতার তথ্য (প্রথমবার)
    const dlg = p.getByRole('dialog').last();
    await dlg.getByText('রক্তের গ্রুপ').first().waitFor({ timeout: 10000 });
    await dlg.getByRole('button', { name: 'O-', exact: true }).click();
    await dlg.getByLabel('জন্ম তারিখ').fill('1995-03-15');
    await dlg.getByLabel('ওজন (কেজি)').fill('64');
    await dlg.getByText('কখনো রক্ত দিইনি').click();
    await J.shot('রক্তদাতার তথ্য', 'গ্রুপ O-, জন্ম তারিখ, ওজন ৬৪, ‘কখনো রক্ত দিইনি’ — ‘এগিয়ে যান’।', { page: p });
    await dlg.getByRole('button', { name: 'এগিয়ে যান' }).click();
    await until(p, () => !document.querySelector('[role="dialog"]'), null, 15000);
    await settle(p, 600);
    const card2 = p.locator('section, article, div').filter({ hasText: 'জরুরি আহ্বান · Lifolo টিম' }).filter({ hasText: HOSPITAL }).last();
    if (!(await card2.getByText('আপনি আসছেন').count())) {
      const come = card2.getByRole('button', { name: 'আসছি' }).first();
      if (await come.count()) await come.click();
    }
    await card2.getByText(/আপনি আসছেন — ধন্যবাদ/).waitFor({ timeout: 15000 });
    await J.shot('আসছেন', '‘আপনি আসছেন — ধন্যবাদ! সাথে পরিচয়পত্র আনুন।’ — O- ১/২; ‘রক্ত দিয়েছি’, ‘আসতে পারছি না’।', { page: p, full: true });
  });

  await J.sc('ADM-09', 'অ্যাডমিন: গ্রুপ বন্ধ/খোলা, আরও ডোনার জানানো, আহ্বান শেষ — ডোনার জানেন', async () => {
    page = await owner(J, '#/emergency');
    let card = page.locator('.card').filter({ hasText: HOSPITAL }).first();
    await card.getByText(/১\/২|১ \/ ২|আসছেন ১/).first().waitFor({ timeout: 10000 }).catch(() => {});
    await J.shot('অ্যাডমিন: একজন আসছেন', `${HOSPITAL} — O-: ১ জন আসছেন।`, { full: true });
    await adminDo(page, card, 'এই গ্রুপ বন্ধ', 'O- বন্ধ করা হয়েছে');
    card = page.locator('.card').filter({ hasText: HOSPITAL }).first();
    await card.getByRole('button', { name: 'আবার খুলুন' }).waitFor({ timeout: 10000 });
    await J.shot('O- গ্রুপ বন্ধ', '‘O- বন্ধ করা হয়েছে’ — পাশে ‘আবার খুলুন’।');
    await adminDo(page, card, 'আবার খুলুন', 'O- আবার খোলা হয়েছে');
    card = page.locator('.card').filter({ hasText: HOSPITAL }).first();
    await card.getByRole('button', { name: 'আরও ডোনার জানান' }).click();
    await page.waitForTimeout(200);
    const armed = page.getByRole('button', { name: 'নিশ্চিত? আবার চাপুন' });
    if (await armed.count()) await armed.first().click();
    await page.getByText(/জনকে জানানো হয়েছে|নতুন কেউ নেই|আর কাউকে/).first().waitFor({ timeout: 15000 });
    await J.shot('আরও ডোনার জানানো', 'পরের ঢেউ (admin_emergency_more) — কতজনকে জানানো হলো তার বার্তা।');
    card = page.locator('.card').filter({ hasText: HOSPITAL }).first();
    await adminDo(page, card, 'আহ্বান শেষ করুন', 'আহ্বান শেষ, যাঁরা আসছিলেন তাঁদের জানানো হয়েছে');
    await J.shot('আহ্বান শেষ', 'আহ্বান ‘আগের আহ্বান’-এ চলে যায়।', { full: true });
    const p = J.newUser.page;
    await p.goto(`${BASE}/inbox`);
    await settle(p, 600);
    await see(p, 'জরুরি আহ্বান শেষ');
    await J.shot('ডোনারের ঘণ্টা: আহ্বান শেষ', '‘জরুরি আহ্বান শেষ’ — যিনি আসছিলেন তিনি জানলেন।', { page: p });
    await p.goto(`${BASE}/`);
    await settle(p, 600);
    if (await p.getByText(HOSPITAL).count()) throw new Error('শেষ হওয়া আহ্বান এখনো হোমে');
    await J.newUser.ctx.close();
  });
}
