// "ডা." পদবির নিয়ম (backend/docs/PRACTICE.md §৬, সুইচ title_guard): MBBS/BDS (এলোপ্যাথি ডাক্তার) ছাড়া কোনো পেশাজীবী নামের
// আগে "ডা."/"Dr." লিখতে পারেন না — প্রোফাইল এডিটের নামের ঘরে নিচে ছোট লেখা আর সাথে সাথে ভুল (সেভ যায় না), ব্যাজের আবেদনে
// প্রোফাইলের নাম দেখে একই; সার্ভারের title_not_allowed-এর বার্তাও দেখায়। সুইচ বন্ধ থাকলে কিছুই নেই।
// পেশার নাম: অ-এলোপ্যাথিক চিকিৎসক "ডাক্তার" নন — সার্ভারের typeLabel ("হোমিওপ্যাথিক চিকিৎসক") প্রোফাইল আর প্রেসক্রিপশনে।
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, open, type PageDef } from './helpers';

const ERR = 'MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না';
const HINT = 'নামের আগে ‘ডা.’ লিখবেন না — পেশা নিজে থেকেই দেখানো হবে';

type Calls = { action: string; body: any }[];
const base = async (route: Route) => (await route.fetch()).json();

/** সুইচ (title_guard + চিকিৎসা পদ্ধতি) আর "me"-র ব্যাজ বসানো; বাকি উত্তর নকল সার্ভারের */
async function mock(page: Page, guard: boolean, badge: Record<string, unknown> | null, extra: (action: string, body: any, route: Route) => Promise<unknown | undefined> = async () => undefined): Promise<Calls> {
  const seen: Calls = [];
  await page.route('**/api/community', async (route) => {
    const body = route.request().postDataJSON() || {};
    seen.push({ action: body.action, body });
    let out: unknown = await extra(body.action, body, route);
    if (out === null) return; // extra নিজেই উত্তর দিয়েছে
    if (out === undefined && body.action === 'features') {
      const j = await base(route);
      out = { ...j, keys: { ...j.keys, title_guard: guard, practice_systems: true } };
    } else if (out === undefined && body.action === 'me') {
      out = { ...(await base(route)), badge };
    }
    if (out === undefined) return route.continue();
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(out) });
  });
  return seen;
}
const last = (seen: Calls, action: string) => seen.filter((c) => c.action === action).at(-1)?.body;

const PROFILE = { name: 'ডা. করিম উদ্দিন', phone: '01712345678', placeName: 'ময়মনসিংহ', lat: 24.75, lng: 90.41, locationSource: 'district', notify: true };
const editPage = (name = PROFILE.name): PageDef => ({ name: 'tg-edit', path: '/profile/edit', test: { user: { uid: 'u1', email: 'karim@example.com' }, profile: { ...PROFILE, name }, lang: 'bn', theme: 'light' } });
const badgePage = (name: string, badge: Record<string, unknown> | null = null): PageDef => ({
  name: 'tg-badge', path: '/health/badge',
  test: { user: { uid: 'u1', email: 'karim@example.com' }, profile: { ...PROFILE, name }, me: { badge, isAdmin: false }, lang: 'bn', theme: 'light' },
});
const HOMEO = { type: 'doctor', system: 'homeopathy', status: 'approved', regNo: 'H-1', workplace: 'X' };

test.describe('প্রোফাইল এডিট: পেশাজীবীর নামের ঘর', () => {
  test('হোমিওপ্যাথ: "ডা." থাকলে সাথে সাথে ভুল, সেভ যায় না; ঠিক করলে নিচে ছোট লেখা, সেভ যায়', async ({ page }) => {
    const seen = await mock(page, true, HOMEO, async (action) => (action === 'profile_update' ? { ok: true } : undefined));
    await open(page, editPage());
    const field = page.getByTestId('profile-name');
    await expect(page.getByText(ERR)).toBeVisible();
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await page.waitForTimeout(300);
    expect(last(seen, 'profile_update')).toBeUndefined();
    // প্রতিটা ধরন
    for (const v of ['Dr. Karim', 'ডাঃ করিম', 'ডাক্তার করিম', 'DR.Karim', 'Doctor Karim', 'ডা করিম']) {
      await field.fill(v);
      await expect(page.getByText(ERR).first()).toBeVisible();
    }
    await field.fill('করিম উদ্দিন');
    await expect(page.getByText(ERR)).toHaveCount(0);
    await expect(page.getByText(HINT)).toBeVisible();
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await expect.poll(() => last(seen, 'profile_update')?.set?.name).toBe('করিম উদ্দিন');
  });

  for (const [what, badge] of [
    ['নার্স', { type: 'nurse', status: 'approved' }],
    ['ফিজিওথেরাপিস্ট', { type: 'physio', status: 'pending' }],
    ['সাইকোলজিস্ট', { type: 'psychologist', status: 'approved' }],
    ['ইউনানি', { type: 'doctor', system: 'unani', status: 'approved' }],
  ] as const) {
    test(`${what}: "Dr." লেখা যায় না`, async ({ page }) => {
      await mock(page, true, badge);
      await open(page, editPage('Dr. Rahima'));
      await expect(page.getByText(ERR)).toBeVisible();
      await page.getByTestId('profile-name').fill('Rahima');
      await expect(page.getByText(HINT)).toBeVisible();
    });
  }

  test('এলোপ্যাথি ডাক্তার (MBBS/BDS) আর সাধারণ ব্যবহারকারী: কিছু নয়, "ডা." দিয়ে সেভ হয়', async ({ page }) => {
    const seen = await mock(page, true, { type: 'doctor', system: 'allopathy', status: 'approved' }, async (action) => (action === 'profile_update' ? { ok: true } : undefined));
    await open(page, editPage());
    await expect(page.getByText(HINT)).toHaveCount(0);
    await expect(page.getByText(ERR)).toHaveCount(0);
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await expect.poll(() => last(seen, 'profile_update')?.set?.name).toBe('ডা. করিম উদ্দিন');
  });

  test('সুইচ বন্ধ: হোমিওপ্যাথও আগের মতো — ছোট লেখা বা ভুল নেই', async ({ page }) => {
    const seen = await mock(page, false, HOMEO, async (action) => (action === 'profile_update' ? { ok: true } : undefined));
    await open(page, editPage());
    await expect(page.getByText(HINT)).toHaveCount(0);
    await expect(page.getByText(ERR)).toHaveCount(0);
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await expect.poll(() => last(seen, 'profile_update')?.set?.name).toBe('ডা. করিম উদ্দিন');
  });

  test('সার্ভার আটকালে (title_not_allowed) তার বার্তা দেখায়', async ({ page }) => {
    await mock(page, false, HOMEO, async (action, _b, route) => {
      if (action !== 'profile_update') return undefined;
      await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ error: ERR, code: 'title_not_allowed' }) });
      return null;
    });
    await open(page, editPage());
    await page.getByRole('button', { name: 'সেভ করুন' }).click();
    await expect(page.getByText(ERR).first()).toBeVisible();
  });
});

test.describe('ব্যাজের আবেদন: প্রোফাইলের নাম', () => {
  test('নার্স বাছলে "Dr." নামে ভুল আর প্রোফাইল এডিটের বোতাম, আবেদন যায় না; ডাক্তার (এলোপ্যাথি) বাছলে যায়', async ({ page }) => {
    const seen = await mock(page, true, null, async (action) => (action === 'badge_apply' ? { ok: true } : undefined));
    await open(page, badgePage('Dr. Rahima'));
    await page.getByRole('button', { name: 'নার্স', exact: true }).click();
    const name = page.getByTestId('badge-name');
    await expect(name).toHaveValue('Dr. Rahima');
    await expect(page.getByText(ERR).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'আগে প্রোফাইল এডিটে নাম ঠিক করুন' })).toBeVisible();
    await page.getByLabel('রেজিস্ট্রেশন / লাইসেন্স নম্বর').fill('N-1');
    await page.getByRole('button', { name: 'আবেদন করুন' }).click();
    await page.waitForTimeout(300);
    expect(last(seen, 'badge_apply')).toBeUndefined();
    // এলোপ্যাথি ডাক্তার: নামের ঘরই নেই, আবেদন যায়
    await page.getByRole('button', { name: 'ডাক্তার', exact: true }).click();
    await page.getByTestId('badge-system').getByRole('button', { name: 'এলোপ্যাথি' }).click();
    await expect(page.getByTestId('badge-name')).toHaveCount(0);
    await page.getByRole('button', { name: 'আবেদন করুন' }).click();
    await expect.poll(() => last(seen, 'badge_apply')).toMatchObject({ type: 'doctor', system: 'allopathy' });
  });

  test('হোমিওপ্যাথি বাছলে নামের নিচে ছোট লেখা (নাম ঠিক থাকলে ভুল নেই)', async ({ page }) => {
    await mock(page, true, null);
    await open(page, badgePage('আনোয়ার হোসেন'));
    await page.getByTestId('badge-system').getByRole('button', { name: 'হোমিওপ্যাথি' }).click();
    await expect(page.getByTestId('badge-name')).toHaveValue('আনোয়ার হোসেন');
    await expect(page.getByText(HINT)).toBeVisible();
    await expect(page.getByText(ERR)).toHaveCount(0);
  });
});

test.describe('পেশার নাম', () => {
  test('প্রেসক্রিপশনের মাথায় সার্ভারের পেশা ("হোমিওপ্যাথিক চিকিৎসক"), "ডাক্তার" নয়', async ({ page }) => {
    await mock(page, true, null, async (action, _b, route) => {
      if (!['rx_mine', 'rx_get'].includes(action)) return undefined;
      const j = await base(route);
      const fix = (r: any) => (r.id === 'rx1' ? { ...r, doctor: { ...r.doctor, name: 'আনোয়ার হোসেন', system: 'homeopathy', systemLabel: 'হোমিওপ্যাথি', typeLabel: 'হোমিওপ্যাথিক চিকিৎসক', typeLabelEn: 'Homeopathic practitioner' }, doctorType: 'doctor', rxRules: { medicines: true, freeText: true, pharmacy: false, tests: true } } : r);
      return action === 'rx_mine' ? { ...j, items: j.items.map(fix) } : { ...j, item: fix(j.item) };
    });
    await open(page, def('health', 'rx-detail'));
    await expect(page.getByTestId('paper-system').first()).toHaveText('হোমিওপ্যাথিক চিকিৎসক');
  });

  test('খোঁজা: হোমিওপ্যাথি বাছলে কেউ না থাকলে "কোনো হোমিওপ্যাথিক চিকিৎসক পাওয়া যায়নি"; ট্যাব "ডাক্তার ও চিকিৎসক"', async ({ page }) => {
    await mock(page, true, null, async (action, body) => (action === 'providers' && body.system === 'homeopathy' ? { items: [], facets: { specialties: [], systems: [] } } : undefined));
    await open(page, def('appointment', 'list'));
    await expect(page.getByRole('tab', { name: 'ডাক্তার ও চিকিৎসক' })).toBeVisible();
    await page.getByTestId('system-chips').getByRole('button', { name: 'হোমিওপ্যাথি' }).click();
    await expect(page.getByText('কোনো হোমিওপ্যাথিক চিকিৎসক পাওয়া যায়নি')).toBeVisible();
  });
});
