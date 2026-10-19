// প্যানেলের অবস্থা, সার্ভার আর রাউটিং (#/pharmacies?s=approved)
// লগইন httpOnly কুকিতে, এই স্ক্রিপ্ট সেটা দেখতে পায় না।
import { reactive, shallowRef, type VNodeChild } from 'vue';
import type { J } from './util';
import { toast } from './util';

export const IDLE_MS = 2 * 3600 * 1000; // ২ ঘণ্টা কিছু না করলে লগআউট (সার্ভারেও একই)
export const WARN_MS = 2 * 60 * 1000; // তার ২ মিনিট আগে সতর্ক করা
export const AUTO_REFRESH_MS = 5 * 60 * 1000;

export interface Route {
  name: string;
  arg: string;
  params: URLSearchParams;
}

export const S = reactive({
  me: null as J,
  overview: null as J,
  overviewAt: 0,
  stats: null as J,
  lists: {} as Record<string, J[]>, // "pharmacies:approved" → items
  loggedIn: false,
  busy: 0,
  lastActive: Date.now(),
  rids: [] as string[],
  editBank: null as J,
  /** কোন পর্দা: শুরু, লগইন, শুরুতেই সমস্যা, প্যানেল */
  view: 'boot' as 'boot' | 'login' | 'failed' | 'shell',
  loginMessage: '',
  loginKey: 0,
  failMessage: '',
  navOpen: false,
  hash: location.hash,
  /** পাতা আঁকার পালা — বাড়লে পাতা নতুন করে */
  routeSeq: 0,
  fresh: false,
  keepY: null as number | null,
  warn: false,
  zoom: null as { src: string; label: string } | null,
});

/** উপরের সারি: শিরোনাম, পাশে ছোট লেখা, ডানে বোতাম */
export const top = shallowRef<{ title: string; sub?: string; actions?: () => VNodeChild }>({ title: '' });
export function setTitle(title: string, sub?: string, actions?: () => VNodeChild) {
  top.value = { title, sub, actions };
  document.title = `${title} — Lifolo অ্যাডমিন`;
}

// =========================================================
// সার্ভার
// =========================================================
export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(message: string, status: number, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
export async function post(path: string, body: Record<string, unknown>): Promise<J> {
  let res: Response;
  try {
    res = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Lifolo-Admin': '1' },
      body: JSON.stringify({ lang: 'bn', ...body }),
    });
  } catch {
    throw new ApiError('ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না। সংযোগ দেখে আবার চেষ্টা করুন।', 0);
  }
  let json: J = {};
  try {
    json = await res.json();
  } catch {
    /* খালি উত্তর */
  }
  if (!res.ok) {
    const err = new ApiError(json.error || 'সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন', res.status, json.code);
    if (S.loggedIn && (res.status === 401 || json.code === 'session_expired')) showLogin(err.message);
    throw err;
  }
  return json;
}
// panel: true — সার্ভার শুধু প্যানেলের জন্য বাড়তি জিনিস দেয় (ব্যাজ সংশোধন, চেম্বারের রিপোর্ট)
export const act = (action: string, body?: Record<string, unknown>) => post('/api/community', { action, panel: true, ...body });

export const itemsOf = (key: string): J[] => (S.overview && S.overview[key] && Array.isArray(S.overview[key].items) ? S.overview[key].items : []);
export const pendingCount = (key: string) => itemsOf(key).length;

export async function loadOverview() {
  const ov = await act('admin_overview');
  S.overview = ov;
  S.overviewAt = Date.now();
  S.me = ov.me;
  // অপেক্ষার তালিকা ক্যাশে
  S.lists['badges:pending'] = itemsOf('badges');
  S.lists['pharmacies:pending'] = itemsOf('pharmacies');
  S.lists['ambulances:pending'] = itemsOf('ambulances');
  S.lists['bloodBanks:pending'] = itemsOf('bloodBanks');
  S.lists['labs:pending'] = itemsOf('labs');
  S.lists['tickets:open'] = itemsOf('tickets');
}

export async function getStats(fresh: boolean): Promise<J> {
  if (!fresh && S.stats && Date.now() - S.stats._got < 10 * 60 * 1000) return S.stats;
  const st = await act('admin_stats', fresh ? { fresh: true } : {});
  st._got = Date.now();
  S.stats = st;
  return st;
}

const LIST_ACTION: Record<string, string> = { badges: 'admin_badges', pharmacies: 'admin_pharmacies', ambulances: 'admin_ambulances', bloodBanks: 'admin_bloodbanks', labs: 'admin_labs', tickets: 'admin_tickets' };
export async function getList(kind: string, status: string): Promise<J[]> {
  const key = `${kind}:${status}`;
  if (S.lists[key]) return S.lists[key];
  const res = await act(LIST_ACTION[kind], { status });
  S.lists[key] = res.items || [];
  return S.lists[key];
}

/** অন্য অবস্থার তালিকা আবার আনা হবে */
export function dropLists() {
  for (const k of Object.keys(S.lists)) if (!/:(pending|open)$/.test(k)) delete S.lists[k];
}

// =========================================================
// লগইন / লগআউট / শুরু
// =========================================================
export function showLogin(message?: string) {
  S.loggedIn = false;
  S.overview = null;
  S.lists = {};
  S.stats = null;
  S.loginMessage = message || '';
  S.loginKey++;
  S.view = 'login';
}

export async function logout(message?: string) {
  try {
    await act('web_logout');
  } catch {
    /* মেয়াদ শেষ হলেও লগইন পাতা */
  }
  showLogin(message);
}

export async function start() {
  try {
    await loadOverview();
  } catch (e) {
    const err = e as ApiError;
    if (err.status === 401 || err.status === 403 || err.code === 'session_expired') {
      showLogin(err.status === 403 ? err.message : '');
    } else {
      S.failMessage = err.message;
      S.view = 'failed';
    }
    return;
  }
  S.loggedIn = true;
  S.navOpen = false;
  S.view = 'shell';
  route();
}

// =========================================================
// রাউটিং
// =========================================================
export function parseHash(hash = S.hash): Route {
  const raw = hash.replace(/^#\/?/, '');
  const [path, query] = raw.split('?');
  const parts = path.split('/');
  const params = new URLSearchParams(query || '');
  return { name: parts[0] || '', arg: parts[1] ? decodeURIComponent(parts[1]) : '', params };
}
export const go = (hash: string) => {
  location.hash = hash;
};

/**
 * পাতা আঁকা। fresh = মেনু থেকে আসা: সার্ভার থেকে নতুন তথ্য এনে তারপর আঁকা
 * (আগে লগইনের সময়ের তথ্যই দেখাত, নতুন রিপোর্ট/আবেদন দেখতে ব্রাউজার রিলোড লাগত)
 */
export function route(keepScroll = false, fresh = false) {
  if (!S.loggedIn) return;
  S.hash = location.hash;
  S.fresh = fresh;
  S.keepY = keepScroll ? window.scrollY : null;
  S.routeSeq++;
  if (!keepScroll) window.scrollTo(0, 0);
}

export async function refreshAll() {
  try {
    await loadOverview();
    dropLists();
    route(true);
    toast('হালনাগাদ হয়েছে');
  } catch (e) {
    if (S.loggedIn) toast((e as Error).message);
  }
}

/** কাজ শেষে: অপেক্ষার সংখ্যা হালনাগাদ, এই তালিকা আবার আঁকা */
export async function afterChange() {
  try {
    await loadOverview();
  } catch {
    /* পরে আবার হবে */
  }
  dropLists();
  route(true);
}
