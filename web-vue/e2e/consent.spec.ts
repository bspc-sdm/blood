// স্বাস্থ্য তথ্যের সম্মতি (backend/docs/CONSENT.md), সুইচ health_consent: বন্ধ থাকলে কিছুই বদলায় না; চালু থাকলে
// স্বাস্থ্যের পাতা খোলায় একবার পর্দা, সার্ভারের 428 consent_required → পর্দা → "রাজি আছি" → একই কাজ আবার (consent_grant-এ দুটো
// সম্মতি, ভার্সন, উৎস web), "এখন না" → কাজ হয় না; সেটিংস › গোপনীয়তা: অবস্থা, পুরো লেখা, তুলে নেওয়া (মোছার পাতার লিংকসহ)।
// পর্দার লেখা সার্ভারের আসল ফাইল থেকে (backend/src/main/resources/consent/health-consent.json) — নকল সার্ভারের ওপর page.route।
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page, type Route } from '@playwright/test';
import { def, noSideScroll, open, watchErrors } from './helpers';

const here = path.dirname(fileURLToPath(import.meta.url));
const DOC = JSON.parse(fs.readFileSync(path.join(here, '../../backend/src/main/resources/consent/health-consent.json'), 'utf8'));
const BOTH = ['health_data', 'data_abroad'];

type Calls = { action: string; body: any }[];
const scopes = (state: string, at: number | null = null) => Object.fromEntries(BOTH.map((k) => [k, { state, version: state === 'none' ? null : DOC.version, at, source: at ? 'web' : null }]));
const status = (o: Record<string, unknown> = {}) => ({
  on: true, version: DOC.version, current: false, ask: true, needed: BOTH, grantedAt: null, scopes: scopes('none'), history: [], text: DOC.bn, ...o,
});
const GRANTED = () => status({ current: true, ask: false, needed: [], grantedAt: Date.UTC(2026, 9, 23, 5), scopes: scopes('granted', Date.UTC(2026, 9, 23, 5)) });

/** নকল সার্ভারে সম্মতি: health_consent চালু (on), consent_status/grant/withdraw, আর rx_claim সম্মতি না থাকলে 428 */
async function mock(page: Page, opts: { on?: boolean; start?: Record<string, unknown> } = {}): Promise<Calls> {
  const seen: Calls = [];
  let st: Record<string, unknown> = opts.start ?? status();
  await page.route('**/api/community', async (route: Route) => {
    const body = route.request().postDataJSON() || {};
    seen.push({ action: body.action, body });
    const json = (out: unknown, s = 200) => route.fulfill({ status: s, contentType: 'application/json', body: JSON.stringify(out) });
    switch (body.action) {
      case 'features': {
        const base = await (await route.fetch()).json();
        return json({ ...base, keys: { ...base.keys, health_consent: opts.on !== false } });
      }
      case 'consent_status':
        return json(st);
      case 'consent_grant':
        st = GRANTED();
        return json({ ...st, ok: true, recorded: body.scopes });
      case 'consent_withdraw':
        st = status({ ask: true, scopes: scopes('withdrawn', Date.UTC(2026, 9, 24, 5)) });
        return json({ ...st, ok: true, recorded: BOTH });
      case 'rx_claim':
        if (!(st as { current?: boolean }).current && opts.on !== false) {
          return json({ error: DOC.bn.required, code: 'consent_required', scopes: BOTH, version: DOC.version, caregiver: false }, 428);
        }
        return json({ ok: true, added: 1 });
      default:
        return route.continue();
    }
  });
  return seen;
}
const count = (seen: Calls, a: string) => seen.filter((c) => c.action === a).length;

async function claim(page: Page) {
  await page.getByRole('button', { name: 'কোড দিয়ে যোগ করুন' }).first().click();
  const d = page.getByRole('dialog', { name: 'প্রেসক্রিপশন যোগ করুন' });
  await d.getByLabel('কোড').fill('K7M2Q9XA4T');
  await d.getByLabel('মোবাইল নম্বর').fill('01712345678');
  await d.getByRole('button', { name: 'যোগ করুন', exact: true }).click();
  return d;
}

test.describe('সুইচ বন্ধ', () => {
  test('কিছুই বদলায় না: পর্দা নেই, সেটিংসে গোপনীয়তার কার্ড নেই, সম্মতির কোনো ডাক নেই', async ({ page }) => {
    const errs = watchErrors(page);
    const seen = await mock(page, { on: false });
    await open(page, def('health', 'rx-list'));
    await claim(page);
    await expect.poll(() => count(seen, 'rx_claim')).toBe(1);
    await expect(page.getByTestId('consent-dialog')).toHaveCount(0);
    await open(page, def('account', 'settings'));
    await expect(page.getByRole('heading', { name: 'ভাষা' })).toBeVisible();
    await expect(page.getByTestId('consent-settings')).toHaveCount(0);
    expect(seen.filter((c) => c.action.startsWith('consent_'))).toEqual([]);
    expect(errs).toEqual([]);
  });
});

test.describe('সুইচ চালু', () => {
  test('স্বাস্থ্যের পাতা খুললে একবার পর্দা — সার্ভারের লেখা, ছয়টা অংশ, দুটো সম্মতি, মোবাইলে পাশে স্ক্রল নেই', async ({ page }) => {
    const errs = watchErrors(page);
    const seen = await mock(page);
    await open(page, def('health', 'rx-list'));
    const dlg = page.getByRole('dialog', { name: DOC.bn.title });
    await expect(dlg).toBeVisible();
    for (const s of DOC.bn.sections) await expect(dlg.getByRole('heading', { name: s.heading })).toBeVisible();
    await expect(dlg.getByText(DOC.bn.scopes.health_data)).toBeVisible();
    await expect(dlg.getByText(DOC.bn.scopes.data_abroad)).toBeVisible();
    await expect(dlg.getByRole('link', { name: DOC.bn.sections.find((s: any) => s.key === 'how_long').linkLabel })).toHaveAttribute('href', /lifolo\.com\/privacy#/);
    await expect(dlg.getByRole('button', { name: 'রাজি আছি' })).toBeVisible();
    await noSideScroll(page);
    await dlg.getByRole('button', { name: 'এখন না' }).click();
    await expect(dlg).toHaveCount(0);
    expect(count(seen, 'consent_grant')).toBe(0);
    expect(errs).toEqual([]);
  });

  test('428-এর পর পর্দা → "রাজি আছি" → দুটো সম্মতি একসাথে, তারপর একই কাজ আবার @desktop', async ({ page }) => {
    const errs = watchErrors(page);
    const seen = await mock(page);
    await open(page, def('health', 'rx-list'));
    // পাতা খোলার পর্দা: এখন না
    await page.getByRole('dialog', { name: DOC.bn.title }).getByRole('button', { name: 'এখন না' }).click();
    const claimDlg = await claim(page);
    const dlg = page.getByRole('dialog', { name: DOC.bn.title });
    await expect(dlg).toBeVisible();
    await dlg.getByRole('button', { name: 'রাজি আছি' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(claimDlg).toHaveCount(0);
    expect(count(seen, 'rx_claim')).toBe(2);
    const g = seen.find((c) => c.action === 'consent_grant')!.body;
    expect([g.scopes, g.version, g.source]).toEqual([BOTH, DOC.version, 'web']);
    // দুটো rx_claim একই
    const claims = seen.filter((c) => c.action === 'rx_claim').map((c) => c.body);
    expect(claims[1]).toEqual(claims[0]);
    expect(errs).toEqual([]);
  });

  test('428-এর পর "এখন না" → কাজ হয় না, সাধারণ বার্তা, আবার পাঠানো হয় না', async ({ page }) => {
    const seen = await mock(page);
    await open(page, def('health', 'rx-list'));
    await page.getByRole('dialog', { name: DOC.bn.title }).getByRole('button', { name: 'এখন না' }).click();
    const claimDlg = await claim(page);
    await page.getByRole('dialog', { name: DOC.bn.title }).getByRole('button', { name: 'এখন না' }).click();
    await expect(claimDlg.getByText('সেভ হয়নি। এর জন্য স্বাস্থ্য তথ্যের সম্মতি লাগে।')).toBeVisible();
    expect(count(seen, 'rx_claim')).toBe(1);
    expect(count(seen, 'consent_grant')).toBe(0);
  });

  test('সেটিংস › গোপনীয়তা: অবস্থা ও তারিখ, পুরো লেখা, তুলে নেওয়া (কিছু মোছে না, মোছার পাতার লিংক)', async ({ page }) => {
    const errs = watchErrors(page);
    const seen = await mock(page, { start: GRANTED() });
    await open(page, def('account', 'settings'));
    const card = page.getByTestId('consent-settings');
    await expect(card.getByRole('heading', { name: 'গোপনীয়তা' })).toBeVisible();
    await expect(card.getByTestId('consent-state-health_data')).toContainText('দেওয়া আছে');
    await expect(card.getByTestId('consent-state-data_abroad')).toContainText('ওয়েবে');
    // পুরো লেখা: শুধু পড়া
    await card.getByRole('button', { name: 'পুরো লেখা দেখুন' }).click();
    const full = page.getByRole('dialog', { name: DOC.bn.title });
    await expect(full.getByRole('heading', { name: DOC.bn.sections[0].heading })).toBeVisible();
    await expect(full.getByRole('button', { name: 'রাজি আছি' })).toHaveCount(0);
    await full.getByRole('button', { name: 'বন্ধ', exact: true }).last().click();
    await expect(full).toHaveCount(0);
    // তুলে নেওয়া
    await card.getByRole('button', { name: DOC.bn.withdrawButton }).click();
    const confirm = page.getByRole('dialog', { name: DOC.bn.withdrawTitle });
    await expect(confirm.getByText(DOC.bn.withdrawBody)).toBeVisible();
    await expect(confirm.getByRole('button', { name: DOC.bn.deleteAccountLabel })).toBeVisible();
    await noSideScroll(page);
    await confirm.getByRole('button', { name: DOC.bn.withdrawButton }).click();
    await expect(confirm).toHaveCount(0);
    await expect(card.getByTestId('consent-state-health_data')).toContainText('তুলে নিয়েছেন');
    expect(seen.find((c) => c.action === 'consent_withdraw')!.body).toMatchObject({ scope: 'all', source: 'web' });
    // আবার দেওয়া যায়
    await expect(card.getByRole('button', { name: 'সম্মতি দিন' })).toBeVisible();
    expect(errs).toEqual([]);
  });

  test('তুলে নেওয়ার পর্দা থেকে অ্যাকাউন্ট মোছার পাতা', async ({ page }) => {
    await mock(page, { start: GRANTED() });
    await open(page, def('account', 'settings'));
    await page.getByTestId('consent-settings').getByRole('button', { name: DOC.bn.withdrawButton }).click();
    await page.getByRole('dialog', { name: DOC.bn.withdrawTitle }).getByRole('button', { name: DOC.bn.deleteAccountLabel }).click();
    await expect(page).toHaveURL(/\/delete-account$/);
  });
});
