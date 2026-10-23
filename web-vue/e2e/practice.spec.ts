// চিকিৎসা পদ্ধতি + নতুন পেশাজীবী (backend/docs/PRACTICE.md): সুইচ বন্ধ থাকলে আগের মতো (পদ্ধতির চিপ/লেবেল/বাছাই নেই,
// ফিজিও/সাইকোলজিস্টের ট্যাব নেই, সার্ভারে system যায় না); চালু থাকলে তালিকার চিপ আর লেবেল, নতুন ট্যাব, ব্যাজের আবেদনে
// পদ্ধতি আর নিবন্ধনের প্রতিষ্ঠান, প্রেসক্রিপশনের নিয়ম (নিজে লেখা ওষুধ / শুধু পরামর্শ ও ব্যায়াম / সেশন নোট), রোগীর কাগজে
// পদ্ধতি আর ফার্মেসিতে পাঠানো নেই, কেয়ারগিভারকে সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট দেখানোর সুইচ, অ্যাডমিনের যাচাইয়ে পদ্ধতি।
// নকল সার্ভারের উত্তরের ওপর এই অংশ পরীক্ষা নিজেই বসায় (page.route) — test/fixtures বদলানো হয়নি।
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, open, watchErrors } from './helpers';

type Calls = { action: string; body: any }[];
const ON = { practice_systems: true, pro_physio: true, pro_psychologist: true };
/** test/fixtures-e2e/features.mjs সব চালু দেয় — বন্ধের পরীক্ষায় এই তিনটা নিজে বন্ধ */
const OFF = { practice_systems: false, pro_physio: false, pro_psychologist: false };
/** অন্য সুইচ সার্ভারের পরীক্ষার প্রোফাইলের মতো চালু; এই তিনটা keys অনুযায়ী */
async function mock(page: Page, keys: Record<string, boolean>, extra: (action: string, body: any, route: Route) => Promise<unknown | undefined> = async () => undefined): Promise<Calls> {
  const seen: Calls = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    seen.push({ action: body.action, body });
    let out: unknown;
    if (body.action === 'features') {
      const base = await (await route.fetch()).json();
      out = { ...base, keys: { ...base.keys, ...keys } };
    } else out = await extra(body.action, body, route);
    if (out === undefined) return route.continue();
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(out) });
  });
  return seen;
}
const base = async (route: Route) => (await route.fetch()).json();
const last = (seen: Calls, action: string) => seen.filter((c) => c.action === action).at(-1)?.body;

const doc = (uid: string, name: string, system: string | null, o: Record<string, unknown> = {}) => ({
  uid, type: 'doctor', name, specialties: system && system !== 'allopathy' ? ['alt_general'] : ['medicine'], degrees: system === 'homeopathy' ? 'DHMS' : 'MBBS', gender: 'male',
  listed: true, appointments: true, rating: 0, ratingCount: 0, photo: '', homeVisit: null,
  chambers: [{ mode: 'in_person', hospitalId: 'h1', hospitalName: 'Demo Clinic', hospitalNameBn: 'ডেমো ক্লিনিক', district: 'ময়মনসিংহ', upazilaId: null, unionId: null, room: '', schedule: '', fee: '৩০০ টাকা', noFee: false, appointments: true, serialPhone: '', days: [0, 1, 2, 3, 4, 5, 6], start: '10:00', end: '13:00' }],
  ...(system ? { system } : {}), ...o,
});
const HOMEO = doc('p_homeo', 'মো. করিম উদ্দিন', 'homeopathy');
const PHYSIO = { ...doc('p_physio', 'রিফাত হাসান', null, { type: 'physio', specialties: ['physio_back_neck'], degrees: 'BPT' }) };

test.describe('সুইচ বন্ধ: সাইট আগের মতো', () => {
  test('ডাক্তারের তালিকা: পদ্ধতির চিপ, লেবেল, নতুন ট্যাব নেই; সার্ভারে system যায় না @desktop', async ({ page }) => {
    const errs = watchErrors(page);
    const seen = await mock(page, OFF, async (action, _b, route) => {
      if (action !== 'providers') return undefined;
      const j = await base(route);
      return { ...j, items: [...j.items, HOMEO] };
    });
    await open(page, def('appointment', 'list'));
    await expect(page.locator('.card', { hasText: 'মো. করিম উদ্দিন' })).toBeVisible();
    await expect(page.getByTestId('system-chips')).toHaveCount(0);
    await expect(page.locator('.title-tag')).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'ফিজিওথেরাপিস্ট' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'সাইকোলজিস্ট' })).toHaveCount(0);
    expect(last(seen, 'providers').system).toBeUndefined();
    // সরাসরি লিংকে নতুন ধরনও ডাক্তারের তালিকাই
    await page.goto('/appointment?t=physio', { waitUntil: 'networkidle' });
    expect(last(seen, 'providers').type).toBe('doctor');
    expect(errs).toEqual([]);
  });

  test('ব্যাজের আবেদন: পদ্ধতির বাছাই আর নতুন পেশা নেই, system পাঠানো হয় না', async ({ page }) => {
    const seen = await mock(page, OFF, async (action) => (action === 'me' ? { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: true, unread: 0 } : action === 'badge_apply' ? { ok: true } : undefined));
    await open(page, def('health', 'badge-new'));
    await expect(page.getByRole('button', { name: 'ডাক্তার' })).toBeVisible();
    await expect(page.getByTestId('badge-system')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'ফিজিওথেরাপিস্ট' })).toHaveCount(0);
    await expect(page.getByLabel('রেজিস্ট্রেশন / লাইসেন্স নম্বর')).toHaveAttribute('placeholder', 'BMDC A-12345');
    await page.getByLabel('রেজিস্ট্রেশন / লাইসেন্স নম্বর').fill('A-11111');
    await page.getByRole('button', { name: 'আবেদন করুন' }).click();
    await expect.poll(() => last(seen, 'badge_apply')).toBeTruthy();
    expect(last(seen, 'badge_apply').system).toBeUndefined();
  });

  test('প্রেসক্রিপশন লেখা আগের মতো: ওষুধের তালিকা থেকে খোঁজা, পরীক্ষা', async ({ page }) => {
    await mock(page, OFF);
    await open(page, def('rxdoctor', 'write'));
    await expect(page.getByRole('combobox', { name: /ওষুধের নাম/ }).first()).toBeVisible();
    await expect(page.getByTestId('rx-free-note')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'পরীক্ষা', exact: true })).toBeVisible();
  });

  test('কেয়ারগিভার: সাইকোলজিস্টের সুইচ নেই', async ({ page }) => {
    await mock(page, OFF);
    await open(page, def('account', 'care'));
    await expect(page.getByText('tanvir.demo@example.com')).toBeVisible();
    await expect(page.getByTestId('psych-share')).toHaveCount(0);
  });
});

test.describe('সুইচ চালু', () => {
  test('তালিকা: সব / এলোপ্যাথি / … চিপ, নামের পাশে পদ্ধতি, ছাঁকনিতে system; নতুন ট্যাব @desktop', async ({ page }) => {
    const errs = watchErrors(page);
    const seen = await mock(page, ON, async (action, body, route) => {
      if (action !== 'providers') return undefined;
      if (body.type === 'physio') return { items: [PHYSIO] };
      if (body.system === 'homeopathy') return { items: [HOMEO] };
      const j = await base(route);
      return { ...j, items: [...j.items.map((p: any) => ({ ...p, system: 'allopathy' })), HOMEO] };
    });
    await open(page, def('appointment', 'list'));
    const chips = page.getByTestId('system-chips');
    await expect(chips.getByRole('button')).toHaveText(['সব', 'এলোপ্যাথি', 'হোমিওপ্যাথি', 'ইউনানি', 'আয়ুর্বেদ']);
    await expect(chips.getByRole('button', { name: 'সব' })).toHaveAttribute('aria-pressed', 'true');
    const homeo = page.locator('.card', { hasText: 'মো. করিম উদ্দিন' });
    await expect(homeo.locator('.title-tag')).toHaveText('হোমিওপ্যাথি');
    await expect(homeo).not.toContainText('ডা.');
    await expect(page.locator('.card', { hasText: 'ডা. মাহমুদা আক্তার' }).locator('.title-tag')).toHaveText('এলোপ্যাথি');

    await chips.getByRole('button', { name: 'হোমিওপ্যাথি' }).click();
    await expect.poll(() => last(seen, 'providers')?.system).toBe('homeopathy');
    await expect(page.getByText('ডা. মাহমুদা আক্তার')).toHaveCount(0);
    // বিস্তারিত: নামের নিচে পদ্ধতি
    await homeo.click();
    await expect(page.getByTestId('detail-system').first()).toHaveText('হোমিওপ্যাথি');

    // নতুন ট্যাব: ফিজিওথেরাপিস্ট — পদ্ধতির চিপ নেই, type: physio
    await page.goto('/appointment', { waitUntil: 'networkidle' });
    await page.getByRole('tab', { name: 'ফিজিওথেরাপিস্ট' }).click();
    await expect.poll(() => last(seen, 'providers')?.type).toBe('physio');
    expect(last(seen, 'providers').system).toBeUndefined();
    await expect(page.getByTestId('system-chips')).toHaveCount(0);
    await expect(page.locator('.card', { hasText: 'রিফাত হাসান' })).toContainText('কোমর ও ঘাড় ব্যথা');
    await expect(page.getByRole('tab', { name: 'সাইকোলজিস্ট' })).toBeVisible();
    expect(errs).toEqual([]);
  });

  test('ব্যাজ: ডাক্তারের পদ্ধতি, নিবন্ধনের প্রতিষ্ঠান আর ডিগ্রির ইঙ্গিত; অ-এলোপ্যাথির সনদ লাগবেই; ফিজিওর কাগজ', async ({ page }) => {
    const seen = await mock(page, ON, async (action) => (action === 'me' ? { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: true, unread: 0 } : action === 'badge_apply' ? { ok: true } : undefined));
    await open(page, def('health', 'badge-new'));
    const sys = page.getByTestId('badge-system');
    await expect(sys).toContainText('নিবন্ধন: BMDC · ডিগ্রি: MBBS / BDS');
    await sys.getByRole('button', { name: 'হোমিওপ্যাথি' }).click();
    await expect(sys).toContainText('নিবন্ধন: বাংলাদেশ হোমিওপ্যাথিক বোর্ড · ডিগ্রি: DHMS / BHMS');
    await expect(page.getByText('বাংলাদেশ হোমিওপ্যাথিক বোর্ড-এর নিবন্ধন সনদ')).toBeVisible();
    await page.getByLabel('বাংলাদেশ হোমিওপ্যাথিক বোর্ড নিবন্ধন নম্বর').fill('H-2024-77');
    await page.getByRole('button', { name: 'আবেদন করুন' }).click();
    await expect(page.getByRole('alert')).toContainText('বাংলাদেশ হোমিওপ্যাথিক বোর্ড-এর নিবন্ধন সনদ');
    expect(last(seen, 'badge_apply')).toBeUndefined();
    // ইউনানি আর আয়ুর্বেদ — একই বোর্ড, আলাদা ডিগ্রি
    await sys.getByRole('button', { name: 'আয়ুর্বেদ' }).click();
    await expect(sys).toContainText('নিবন্ধন: বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড · ডিগ্রি: DAMS / BAMS');
    // এলোপ্যাথিতে আগের মতো: সনদ ঐচ্ছিক, system যায়
    await sys.getByRole('button', { name: 'এলোপ্যাথি' }).click();
    await page.getByRole('button', { name: 'আবেদন করুন' }).click();
    await expect.poll(() => last(seen, 'badge_apply')).toMatchObject({ type: 'doctor', system: 'allopathy' });
  });

  test('ব্যাজ: ফিজিওথেরাপিস্ট আর সাইকোলজিস্ট — পেশা বাছাই, কাগজের ইঙ্গিত, পদ্ধতি নেই', async ({ page }) => {
    await mock(page, ON, async (action) => (action === 'me' ? { badge: null, isAdmin: false, isOwner: false, phoneCheck: false, phoneVerified: true, unread: 0 } : undefined));
    await open(page, def('health', 'badge-new'));
    await page.getByRole('button', { name: 'ফিজিওথেরাপিস্ট' }).click();
    await expect(page.getByTestId('badge-system')).toHaveCount(0);
    await expect(page.getByTestId('type-hint')).toContainText('BPT / MPT');
    await expect(page.getByText('ডিগ্রির সনদ (BPT / MPT)')).toBeVisible();
    await expect(page.getByText('পেশাজীবী সংগঠনের সদস্যপত্র')).toBeVisible();
    await page.getByRole('button', { name: 'ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর' }).click();
    await expect(page.getByText('বাংলাদেশ ক্লিনিক্যাল সাইকোলজি সোসাইটির সদস্যপত্র')).toBeVisible();
  });

  test('ব্যাজ সংশোধন: পদ্ধতি বদলালে আবার যাচাইয়ের কথা', async ({ page }) => {
    await mock(page, ON);
    await open(page, def('health', 'badge-approved'));
    await expect(page.getByText('ডাক্তার · এলোপ্যাথি')).toBeVisible();
    const sys = page.getByTestId('badge-system');
    await sys.getByRole('button', { name: 'ইউনানি' }).click();
    await expect(sys).toContainText('চিকিৎসা পদ্ধতি বদলালে ব্যাজ আবার যাচাইয়ে যাবে।');
  });

  test('প্রেসক্রিপশন: হোমিওপ্যাথ — ওষুধ নিজে লেখা (তালিকা/ধরন/মাত্রা নেই), পরীক্ষা চলে, কাগজে পদ্ধতি @desktop', async ({ page }) => {
    const seen = await mock(page, ON, async (action, _b, route) => {
      if (action === 'rx_me') return { ...(await base(route)), system: 'homeopathy', type: 'doctor', rxRules: { medicines: true, freeText: true, pharmacy: false, tests: true } };
      return undefined;
    });
    await open(page, def('rxdoctor', 'write'));
    await expect(page.getByTestId('rx-free-note')).toBeVisible();
    await expect(page.locator('[data-f=name]')).toHaveCount(1);
    await expect(page.locator('[data-f=name][role=combobox]')).toHaveCount(0);
    await expect(page.locator('[data-f=form]')).toHaveCount(0);
    await page.locator('[data-f=name]').first().fill('Nux Vomica 30');
    await page.locator('[data-f=dose]').first().fill('৪ ফোঁটা দিনে ৩ বার');
    await expect(page.getByRole('heading', { name: 'পরীক্ষা', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'প্রেসক্রিপশন দিন' }).click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('paper-system')).toHaveText('হোমিওপ্যাথি');
    await dlg.getByRole('button', { name: 'দিন', exact: true }).click();
    await expect.poll(() => last(seen, 'rx_create')).toBeTruthy();
    expect(last(seen, 'rx_create').medicines).toEqual([{ name: 'Nux Vomica 30', dose: '৪ ফোঁটা দিনে ৩ বার', days: 5, note: '' }]);
  });

  test('প্রেসক্রিপশন: ফিজিওথেরাপিস্ট — ওষুধ/পরীক্ষা নেই, "পরামর্শ ও ব্যায়াম"; রিপোর্ট ট্যাব নেই', async ({ page }) => {
    const seen = await mock(page, ON, async (action, _b, route) => {
      if (action === 'me') return { ...(await base(route)), badge: { type: 'physio', status: 'approved', regNo: 'BPT-12' } };
      if (action === 'rx_me') return { ...(await base(route)), type: 'physio', system: null, rxRules: { medicines: false, freeText: false, pharmacy: false, tests: false, section: 'পরামর্শ ও ব্যায়াম' } };
      return undefined;
    });
    await open(page, def('rxdoctor', 'write'));
    await expect(page.getByTestId('rx-no-meds')).toBeVisible();
    await expect(page.locator('[data-f=name]')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'পরীক্ষা', exact: true })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'রিপোর্ট' })).toHaveCount(0);
    await page.getByRole('button', { name: 'প্রেসক্রিপশন দিন' }).click();
    await expect(page.getByText('পরামর্শ লিখুন')).toBeVisible();
    await page.getByLabel('পরামর্শ ও ব্যায়াম').fill('দিনে দুবার ঘাড়ের ব্যায়াম');
    await page.getByRole('button', { name: 'প্রেসক্রিপশন দিন' }).click();
    const dlg = page.getByRole('dialog');
    await expect(dlg.getByTestId('paper-system')).toHaveText('ফিজিওথেরাপিস্ট');
    await dlg.getByRole('button', { name: 'দিন', exact: true }).click();
    await expect.poll(() => last(seen, 'rx_create')).toBeTruthy();
    expect(last(seen, 'rx_create')).toMatchObject({ medicines: [], tests: [], advice: 'দিনে দুবার ঘাড়ের ব্যায়াম' });
  });

  test('প্রেসক্রিপশন: সাইকোলজিস্ট — শুধু সেশন নোট, সাইকিয়াট্রিস্টের কথা', async ({ page }) => {
    await mock(page, ON, async (action, _b, route) => {
      if (action === 'me') return { ...(await base(route)), badge: { type: 'psychologist', status: 'approved', regNo: 'CP-7' } };
      if (action === 'rx_me') return { ...(await base(route)), type: 'psychologist', system: null };
      return undefined;
    });
    await open(page, def('rxdoctor', 'write'));
    await expect(page.getByTestId('rx-psychiatrist')).toHaveText('ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান');
    await expect(page.getByLabel('সেশন নোট ও পরামর্শ')).toBeVisible();
    await expect(page.locator('[data-f=name]')).toHaveCount(0);
  });

  test('রোগীর প্রেসক্রিপশন (হোমিওপ্যাথি): কাগজে পদ্ধতি আর বোর্ড, "ফার্মেসিতে পাঠান" নেই', async ({ page }) => {
    await mock(page, ON, async (action, b, route) => {
      if (!['rx_mine', 'rx_get'].includes(action)) return undefined;
      const j = await base(route);
      const fix = (r: any) => (r.id === 'rx1' ? { ...r, doctor: { ...r.doctor, system: 'homeopathy', systemLabel: 'হোমিওপ্যাথি' }, doctorType: 'doctor', rxRules: { medicines: true, freeText: true, pharmacy: false, tests: true } } : r);
      return action === 'rx_mine' ? { ...j, items: j.items.map(fix) } : { ...j, item: fix(j.item) };
    });
    await open(page, def('health', 'rx-detail'));
    await expect(page.getByTestId('paper-system').first()).toHaveText('হোমিওপ্যাথি');
    await expect(page.getByText(/বাংলাদেশ হোমিওপ্যাথিক বোর্ড/).first()).toBeVisible();
    await expect(page.getByTestId('rx-no-pharmacy')).toBeVisible();
    await expect(page.getByRole('button', { name: 'ফার্মেসিতে পাঠান' })).toHaveCount(0);
  });

  test('কেয়ারগিভার: সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট দেখানোর সুইচ (চালু কেয়ারগিভারে, শুরুতে বন্ধ)', async ({ page }) => {
    const seen = await mock(page, ON, async (action) => (action === 'care_share_psych' ? { ok: true, sharePsych: true } : undefined));
    await open(page, def('account', 'care'));
    const sw = page.getByTestId('psych-share');
    await expect(sw).toHaveCount(1);
    const input = sw.getByRole('switch', { name: /সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট ও নোট/ });
    await expect(input).not.toBeChecked();
    await input.check();
    await expect.poll(() => last(seen, 'care_share_psych')).toMatchObject({ caretakerUid: 'c1', share: true });
  });

  test('আমার অ্যাপয়েন্টমেন্ট: সাইকোলজিস্টের — কেয়ারগিভার দেখেন না লেখা; ডাক্তারের পাশে পদ্ধতি', async ({ page }) => {
    await mock(page, ON, async (action, _b, route) => {
      if (action !== 'my_appointments') return undefined;
      const j = await base(route);
      return {
        ...j,
        items: [
          ...j.items.map((a: any) => (a.id === 'a1' ? { ...a, system: 'unani' } : a)),
          { ...j.items.find((a: any) => a.id === 'a1'), id: 'a_psy', type: 'psychologist', otherName: 'নাসরিন সুলতানা', system: null },
        ],
      };
    });
    await open(page, def('appointment', 'my'));
    await expect(page.locator('.card', { hasText: 'নাসরিন সুলতানা' })).toBeVisible();
    await expect(page.locator('.card', { hasText: 'নাসরিন সুলতানা' }).locator('.title-tag')).toHaveCount(0);
    await expect(page.locator('.title-tag', { hasText: 'ইউনানি' })).toHaveCount(1);
    await page.goto('/my-appointments/a_psy', { waitUntil: 'networkidle' });
    await expect(page.getByTestId('psych-private-note')).toContainText('আপনি অনুমতি না দিলে কেয়ারগিভার সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট আর নোট দেখেন না।');
  });

  test('চেম্বার সেটআপ: পদ্ধতি দেখানো (বদলাতে ব্যাজ), পদ্ধতির বিশেষজ্ঞতা', async ({ page }) => {
    await mock(page, ON, async (action, _b, route) => {
      if (action === 'provider_me') {
        const j = await base(route);
        return { ...j, provider: { ...j.provider, system: 'homeopathy', specialties: ['alt_general'] } };
      }
      return undefined;
    });
    await open(page, def('appointment', 'pro-setup'));
    await expect(page.getByTestId('setup-system')).toContainText('হোমিওপ্যাথি');
    await expect(page.getByRole('button', { name: 'সাধারণ' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'হৃদরোগ' })).toHaveCount(0);
    await expect(page.getByLabel('ডিগ্রি (ঐচ্ছিক)')).toHaveAttribute('placeholder', 'যেমন: DHMS / BHMS');
  });
});

test('অ্যাডমিন যাচাই: হোমিওপ্যাথ ডাক্তারের ব্যাজে পদ্ধতি, বোর্ড আর আগের পদ্ধতি', async ({ page }) => {
  const BADGE = {
    verification: null, change: null, status: 'pending', reviewedAt: null, uid: 'demo-homeo', type: 'doctor', system: 'homeopathy', systemLabel: 'হোমিওপ্যাথি',
    typeLabel: 'ডাক্তার', body: 'বাংলাদেশ হোমিওপ্যাথিক বোর্ড', degreeHint: 'DHMS / BHMS', reverifyFrom: 'allopathy', regNo: 'H-2024-77',
    workplace: 'ডেমো হোমিও চেম্বার', name: 'ডেমো হোমিও ডাক্তার', email: '', createdAt: Date.now(),
  };
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    if (body.action === 'admin_overview') {
      const j = await base(route);
      return route.fulfill({ json: { ...j, badges: { ...j.badges, items: [BADGE, ...(j.badges?.items || [])] } } });
    }
    if (body.action === 'admin_badges' && body.status === 'pending') {
      const j = await base(route);
      return route.fulfill({ json: { ...j, items: [BADGE, ...(j.items || [])] } });
    }
    if (body.action === 'admin_verify_docs' && body.id === 'demo-homeo') {
      return route.fulfill({ json: { photos: [{ type: 'cert', label: 'বাংলাদেশ হোমিওপ্যাথিক বোর্ড-এর নিবন্ধন সনদ', data: null }], docs: [{ type: 'cert', label: 'বাংলাদেশ হোমিওপ্যাথিক বোর্ড-এর নিবন্ধন সনদ', hint: 'নম্বর আর নাম পড়া যায়', required: true, has: true }], checks: [{ id: 'cert_no', label: 'সনদের নিবন্ধন নম্বর আর অ্যাপে লেখা নম্বর এক' }] } });
    }
    return route.continue();
  });
  await page.goto('/admin');
  await page.locator('input[type=email]').fill('owner@example.com');
  await page.getByRole('button', { name: 'কোড পাঠান' }).click();
  await page.locator('input[autocomplete=one-time-code]').fill('১২৩৪৫৬');
  await page.getByRole('button', { name: 'লগইন করুন' }).click();
  await expect(page.locator('.shell')).toBeVisible();
  await page.goto('/admin#/verify/badges:demo-homeo');
  const kv = page.locator('table.kv');
  await expect(kv).toContainText('চিকিৎসা পদ্ধতি');
  await expect(kv).toContainText('হোমিওপ্যাথি (আগে: এলোপ্যাথি)');
  await expect(kv).toContainText('বাংলাদেশ হোমিওপ্যাথিক বোর্ড');
  await expect(kv).toContainText('DHMS / BHMS');
});
