// ভুলের খবর (Sentry, প্রকল্প lifolo-web) — শুধু ভুল (error); কোনো tracing, replay, session বা ব্যক্তিগত তথ্য নয়।
// DSN বিল্ডের সময় আসে (VITE_SENTRY_DSN, Deploy backend workflow-এ SENTRY_DSN_WEB সিক্রেট থেকে); খালি হলে কিছুই লোড হয় না।
// SDK আলাদা অংশে (dynamic import) — মূল পাতার ফাইল বড় হয় না।
// যাচাই: https://lifolo.com/#sentry-test খুললে একটা পরীক্ষার ঘটনা যায় (console-এ "[sentry] …" লেখা)।
import type { App } from 'vue';
import type { ErrorEvent, Breadcrumb } from '@sentry/vue';

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
// বাংলাদেশের মোবাইল (০১XXXXXXXXX, +৮৮০…) আর অন্য দেশের +নম্বর
const PHONE = /(?<![0-9])(?:\+?88)?01[3-9][0-9]{8}(?![0-9])|\+[0-9][0-9 -]{9,16}[0-9]/g;
const JWT = /eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}/g;
const BEARER = /(bearer\s+)[A-Za-z0-9._~+/=-]+/gi;
const KEYVAL = /((?:token|secret|password|passwd|code|key|session|cookie|authorization|phone|email)["']?\s*[:=]\s*["']?)[^\s"'&,;}]+/gi;

export function scrub(s: string): string;
export function scrub(s: string | undefined): string | undefined;
export function scrub(s: string | undefined): string | undefined {
  if (!s) return s;
  return s
    .replace(JWT, '[token]')
    .replace(BEARER, '$1[token]')
    .replace(KEYVAL, '$1[filtered]')
    .replace(EMAIL, '[email]')
    .replace(PHONE, '[phone]');
}

function scrubAny(v: unknown, depth = 0): unknown {
  if (typeof v === 'string') return scrub(v);
  if (depth > 4 || v === null || typeof v !== 'object') return v;
  if (Array.isArray(v)) return v.map((x) => scrubAny(x, depth + 1));
  const out: Record<string, unknown> = {};
  for (const [k, x] of Object.entries(v as Record<string, unknown>)) out[k] = scrubAny(x, depth + 1);
  return out;
}

export function scrubEvent(e: ErrorEvent): ErrorEvent {
  delete e.user;
  if (e.request) {
    delete e.request.cookies;
    delete e.request.data;
    if (e.request.headers) e.request.headers = { 'User-Agent': e.request.headers['User-Agent'] ?? '' };
    e.request.url = scrub(e.request.url);
    if (typeof e.request.query_string === 'string') e.request.query_string = scrub(e.request.query_string);
    else delete e.request.query_string;
  }
  if (e.message) e.message = scrub(e.message);
  for (const x of e.exception?.values ?? []) x.value = scrub(x.value);
  if (e.breadcrumbs) e.breadcrumbs = e.breadcrumbs.map(scrubCrumb);
  if (e.extra) e.extra = scrubAny(e.extra) as Record<string, unknown>;
  return e;
}

export function scrubCrumb(b: Breadcrumb): Breadcrumb {
  if (b.message) b.message = scrub(b.message);
  if (b.data) b.data = scrubAny(b.data) as Record<string, unknown>;
  return b;
}

// শুরুতেই পড়া (router পরে ঠিকানা বদলালেও)
const wantTest = typeof window !== 'undefined' && window.location.hash === '#sentry-test';

export async function initSentry(app: App): Promise<void> {
  const dsn = (import.meta.env.VITE_SENTRY_DSN as string | undefined)?.trim();
  if (!dsn) return;
  try {
    const Sentry = await import('@sentry/vue');
    Sentry.init({
      app,
      dsn,
      environment: 'production',
      release: (import.meta.env.VITE_SENTRY_RELEASE as string | undefined) || undefined,
      sampleRate: 1.0,
      // ব্যক্তিগত তথ্য নয় (v11: sendDefaultPii-এর বদলে dataCollection)
      dataCollection: { userInfo: false, cookies: false, httpHeaders: false, httpBodies: [], urlQueryParams: false },
      // শুধু ভুল: tracing/replay/profiling/session কিছুই নয়
      integrations: (defaults) => defaults.filter((i) => !/BrowserSession|Replay|Tracing|Feedback/i.test(i.name)),
      tracesSampleRate: undefined,
      beforeSend: (event) => scrubEvent(event),
      beforeBreadcrumb: (b) => scrubCrumb(b),
    });
    console.info('[sentry] initialised', Sentry.getClient()?.getOptions().release ?? '');
    if (wantTest) {
      const id = Sentry.captureMessage('lifolo-web: test event (deploy check)', 'info');
      await Sentry.flush(5000);
      console.info('[sentry] test event sent', id);
    }
  } catch (err) {
    console.warn('[sentry] not started', err);
  }
}
