// ডেস্কটপে পাতা চলে https://tauri.localhost থেকে, সার্ভার https://lifolo.com — ওয়েবের কোড `/api/…` (একই ঠিকানা) ডাকে।
// তাই window.fetch-এর ওপর ছোট মোড়ক: `/api/…` আর `https://lifolo.com/…` যায় Rust-এর HTTP দিয়ে (tauri-plugin-http,
// সার্ভারে CORS লাগে না; SSE-ও চলে — শরীর টুকরো টুকরো আসে)। বাকি সব (Firebase, Sentry, LiveKit) আগের মতো ব্রাউজারের fetch।
// ছবি/লিংকে `/api/…` থাকলে (দোকানের QR) পুরো ঠিকানা বসানো।
import { fetch as tauriFetch } from '@tauri-apps/plugin-http';

export const API_ORIGIN = 'https://lifolo.com';

export interface NetEvent {
  /** সার্ভারে পৌঁছানো গেল (যেকোনো উত্তর) */
  reached: boolean;
  status: number;
  /** লগইন করা ডাক (Bearer) */
  authed: boolean;
  /** সার্ভারের Date হেডার (ms) */
  serverDate: number | null;
}

export function toApiUrl(u: string): string | null {
  if (!u) return null;
  if (u.startsWith('/api/')) return API_ORIGIN + u;
  if (u.startsWith(API_ORIGIN + '/')) return u;
  try {
    const x = new URL(u, window.location.href);
    if (x.origin === window.location.origin && x.pathname.startsWith('/api/')) return API_ORIGIN + x.pathname + x.search;
  } catch {
    /* ঠিকানা নয় */
  }
  return null;
}

function hasBearer(input: RequestInfo | URL, init?: RequestInit): boolean {
  const h = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
  return /^Bearer\s/i.test(h.get('authorization') ?? '');
}

let installed = false;
const listeners = new Set<(e: NetEvent) => void>();
const onEvent = (e: NetEvent) => listeners.forEach((f) => f(e));

/** সার্ভারে প্রতিটা ডাকের ফল (৭ দিনের হিসাব, অনলাইন কি না) */
export function onNet(f: (e: NetEvent) => void): () => void {
  listeners.add(f);
  return () => listeners.delete(f);
}

export function installNet() {
  if (installed) return;
  installed = true;
  const native = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const abs = toApiUrl(raw);
    if (!abs) return native(input, init);
    const authed = hasBearer(input, init);
    try {
      const req = input instanceof Request ? new Request(abs, input) : abs;
      // plugin-http init থেকে নিজের চাবি মুছে দেয় — তাই কপি
      const res = await tauriFetch(req, init ? { ...init } : undefined);
      const d = Date.parse(res.headers.get('date') ?? '');
      onEvent({ reached: true, status: res.status, authed, serverDate: Number.isFinite(d) ? d : null });
      return res;
    } catch (e) {
      if (init?.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      onEvent({ reached: false, status: 0, authed, serverDate: null });
      throw new TypeError(`Failed to fetch: ${String(e)}`);
    }
  };
  fixLinks();
}

/** <img src="/api/…"> আর <a href="/api/…"> → https://lifolo.com/api/… */
function fixLinks() {
  const fix = (el: Element) => {
    for (const attr of ['src', 'href'] as const) {
      const v = el.getAttribute(attr);
      if (v && v.startsWith('/api/')) el.setAttribute(attr, API_ORIGIN + v);
    }
  };
  const scan = (root: ParentNode) => root.querySelectorAll?.('img[src^="/api/"], a[href^="/api/"]').forEach(fix);
  const start = () => {
    scan(document);
    new MutationObserver((list) => {
      for (const m of list) {
        if (m.type === 'attributes' && m.target instanceof Element) fix(m.target);
        else m.addedNodes.forEach((n) => n instanceof Element && (fix(n), scan(n)));
      }
    }).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['src', 'href'] });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
}
