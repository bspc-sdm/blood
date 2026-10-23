// "আজকের কাজ" — সার্ভারের `work_today` (POST /api/community; mywork CONTRACT "## Changes", backend/docs/MYWORK.md) এক ডাকে
// প্রতিটা ব্যবসার অবস্থা, আজকের সংখ্যা, "এখনই দেখুন"-এর কাজ আর শুরু করতে যা বাকি দেয়। ডাক্তারের চেম্বারগুলো আর অ্যাম্বুলেন্সের
// গাড়িগুলো এক ব্যবসার ভেতরে আসে — এখানে প্রতিটা আলাদা সারি (অবস্থার নিয়ন্ত্রণ আলাদা), সংখ্যা/কাজ/বাকি প্রথমটায়।
// সার্ভারে কাজটা না থাকলে ("অজানা অনুরোধ") আগের কাজগুলো (chamber_days, queue_today, rx_orders_pharmacy, lab_orders, books_sales,
// med_find_requests, ambreq_driver আর সেশনের s.work) থেকে একই আকার বানানো হয় — পাতা দুই অবস্থাতেই একই।
// অজানা চাবি বাদ; যে চাবি নেই (সুইচ বন্ধ) সেই সংখ্যা/কাজ দেখায় না।
import { computed, ref, watch } from 'vue';
import { call, ServerError } from '@/lib/api';
import { useQ } from '@/lib/query';
import { useSession, type WorkSummary } from '@/lib/session';
import { getLang } from '@/lib/i18n';
import { store } from '@/lib/store';
import { authUid } from '@/lib/uid';
import { isChamberType } from '../../appointment/model';
import { TT } from './text';

export type BizKind = 'chamber' | 'homecare' | 'pharmacy' | 'lab' | 'bloodbank' | 'ambulance';
export type Role = BizKind;
export type Status = 'open' | 'paused' | 'closed';
export const ROLES: Role[] = ['chamber', 'homecare', 'pharmacy', 'lab', 'bloodbank', 'ambulance'];

export interface TodayTask { kind: BizKind; id: string; title: string; sub: string; at: number; action: string; link: string }
export interface SetupItem { key: string; done: boolean }
export interface TodayBiz {
  kind: BizKind;
  id: string;
  name: string;
  status: Status | null;
  pausedUntil: number | null;
  counts: Record<string, number>;
  tasks: TodayTask[];
  setup: SetupItem[];
  /** তালিকায় ওঠার আগে: pending | rejected | suspended (তখন অবস্থা বদলানো যায় না) */
  listing: string | null;
  chamberIndex?: number;
  chamberKey?: string;
  /** অন্য ডাক্তারের চেম্বার (সহকারী) — chamber_day_set-এ যায় */
  doctorUid?: string | null;
  /** আজ এই চেম্বার বসে না */
  noToday?: boolean;
  stockUpdatedAt?: number | null;
  stockStale?: boolean;
}
export interface Today { items: TodayBiz[]; source: 'server' | 'fallback'; at: number }

export const roleLabel = (r: Role) => TT[r]();
export const roleIcon = (r: Role) =>
  r === 'chamber' ? 'stethoscope' : r === 'homecare' ? 'home_health' : r === 'pharmacy' ? 'storefront' : r === 'lab' ? 'local_hospital' : r === 'bloodbank' ? 'bloodtype' : 'ambulance';

// ---------- সার্ভারের উত্তর → এক আকার ----------
const KIND_ALIAS: Record<string, BizKind> = {
  doctor: 'chamber', chamber: 'chamber', homecare: 'homecare', pharmacy: 'pharmacy', lab: 'lab', bloodbank: 'bloodbank', ambulance: 'ambulance',
};
const numOr = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const str = (v: unknown) => (typeof v === 'string' ? v : v == null ? '' : String(v));
const statusOf = (v: unknown): Status | null => (v === 'open' || v === 'paused' || v === 'closed' ? v : null);
const listingOf = (v: unknown) => (v && v !== 'approved' ? str(v) : null);

function normTask(x: any, kind: BizKind): TodayTask | null {
  if (!x || typeof x !== 'object') return null;
  const title = str(x.title);
  if (!title) return null;
  return { kind: KIND_ALIAS[str(x.kind)] ?? kind, id: str(x.id) || title, title, sub: str(x.sub), at: numOr(x.at) ?? 0, action: str(x.action), link: str(x.link) };
}

/** সার্ভারের এক ব্যবসা → এক বা একাধিক সারি (চেম্বারপ্রতি / গাড়িপ্রতি) */
export function normBiz(x: any, opts: { bn?: boolean } = {}): TodayBiz[] {
  if (!x || typeof x !== 'object') return [];
  const kind = KIND_ALIAS[str(x.kind)];
  if (!kind) return [];
  const counts: Record<string, number> = {};
  if (x.counts && typeof x.counts === 'object') for (const [k, v] of Object.entries(x.counts)) if (numOr(v) != null) counts[k] = v as number;
  const tasks = (Array.isArray(x.tasks) ? x.tasks : []).map((t: any) => normTask(t, kind)).filter(Boolean) as TodayTask[];
  const setup = (Array.isArray(x.setup) ? x.setup : []).filter((s: any) => s && s.key).map((s: any) => ({ key: str(s.key), done: s.done === true }));
  const id = str(x.id) || kind;
  const base: TodayBiz = {
    kind, id, name: str(x.name), status: statusOf(x.status), pausedUntil: numOr(x.pausedUntil), counts, tasks, setup, listing: listingOf(x.listingStatus ?? x.listing),
    ...('stockUpdatedAt' in x ? { stockUpdatedAt: numOr(x.stockUpdatedAt) } : {}),
    ...(x.stockStale === true ? { stockStale: true } : {}),
  };
  // প্রথম সারিতেই সংখ্যা/কাজ/বাকি (যোগ করলে দুবার না হয়)
  const rest = { counts: {}, tasks: [], setup: [] };
  if (kind === 'chamber' && Array.isArray(x.chambers)) {
    const assistant = x.role === 'assistant';
    const today = x.chambers.filter((c: any) => c && c.sitsToday !== false);
    const pre = assistant && base.name ? `${base.name} · ` : '';
    if (!today.length) return [{ ...base, id: `${id}:none`, status: null, noToday: true, ...(assistant ? { doctorUid: id } : {}) }];
    return today.map((c: any, i: number): TodayBiz => ({
      ...base,
      ...(i ? rest : {}),
      id: `${id}:${c.chamberIndex ?? i}`,
      name: pre + ((opts.bn && c.nameBn) || c.name || base.name),
      status: statusOf(c.status) ?? (c.closedToday ? 'closed' : 'open'),
      pausedUntil: numOr(c.pausedUntil),
      chamberIndex: numOr(c.chamberIndex) ?? i,
      ...(c.chamberKey ? { chamberKey: str(c.chamberKey) } : {}),
      ...(assistant ? { doctorUid: id } : {}),
    }));
  }
  if (kind === 'ambulance' && Array.isArray(x.vehicles) && x.vehicles.length) {
    return x.vehicles.map((v: any, i: number): TodayBiz => {
      const listing = listingOf(v.listingStatus);
      return { ...base, ...(i ? rest : {}), id: str(v.id), name: str(v.vehicleNo || v.name), listing, status: listing ? null : v.available ? 'open' : 'closed', pausedUntil: null };
    });
  }
  return [base];
}

export function normToday(raw: any, opts: { bn?: boolean } = {}): TodayBiz[] {
  const list = Array.isArray(raw) ? raw : Array.isArray(raw?.items) ? raw.items : [];
  return list.flatMap((x: any) => normBiz(x, opts));
}

/** সার্ভারে work_today নেই (পুরোনো সার্ভার: "অজানা অনুরোধ", বা নকল সার্ভারে উত্তর রাখা নেই) — তখন আগের কাজ থেকে */
export const isMissing = (e: unknown) =>
  e instanceof ServerError && (e.status === 404 || (e.status === 400 && /fixture missing|Unknown request|অজানা অনুরোধ/i.test(e.message))); // bn-ok

// ---------- সংখ্যার ঘর ----------
export interface CountDef { key: string; label: () => string; icon: string; money?: boolean; link: (b: TodayBiz, me: string | null) => string }
const chamberLink = (b: TodayBiz, me: string | null, v: string) => (b.doctorUid && b.doctorUid !== me ? `/work/rx?doctor=${encodeURIComponent(b.doctorUid)}` : `/work/pro?v=${v}`);
export const COUNT_DEFS: Record<BizKind, CountDef[]> = {
  chamber: [
    { key: 'today', label: TT.serials, icon: 'event_outline', link: (b, me) => chamberLink(b, me, 'active') },
    { key: 'waiting', label: TT.waiting, icon: 'schedule', link: (b, me) => chamberLink(b, me, 'active') },
    { key: 'requests', label: TT.requests, icon: 'person_outline', link: (b, me) => chamberLink(b, me, 'new') },
  ],
  homecare: [],
  pharmacy: [
    { key: 'new', label: TT.newOrders, icon: 'medication', link: () => '/work/pharmacy/orders?s=new' },
    { key: 'active', label: TT.activeOrders, icon: 'check_circle', link: () => '/work/pharmacy/orders?s=active' },
    { key: 'salesTodayTk', label: TT.salesToday, icon: 'payments', money: true, link: () => '/work/pharmacy/shop/sales' },
    { key: 'finds', label: TT.finds, icon: 'search', link: () => '/work/pharmacy/finds' },
  ],
  lab: [
    { key: 'new', label: TT.newRequests, icon: 'list_alt', link: () => '/work/lab/orders?s=new' },
    { key: 'active', label: TT.reportsDue, icon: 'edit_note', link: () => '/work/lab/orders?s=active' },
  ],
  bloodbank: [],
  ambulance: [
    { key: 'calls', label: TT.newCalls, icon: 'call', link: () => '/work/ambulance' },
    { key: 'trips', label: TT.trips, icon: 'ambulance', link: () => '/work/ambulance' },
  ],
};

/** একই ভূমিকার সব ব্যবসার সংখ্যা যোগ করে (যেমন দুই চেম্বার) — যে চাবি কোথাও নেই সেটা বাদ */
export function roleTiles(bizs: TodayBiz[], me: string | null) {
  if (!bizs.length) return [];
  const kind = bizs[0].kind;
  return COUNT_DEFS[kind].flatMap((d) => {
    const has = bizs.filter((b) => d.key in b.counts && !b.listing);
    if (!has.length) return [];
    return [{ key: d.key, label: d.label(), icon: d.icon, money: !!d.money, value: has.reduce((a, b) => a + b.counts[d.key], 0), link: d.link(has[0], me) }];
  });
}

/** যেখানে গেলে ঠিক করা যায় (শুরু করতে যা বাকি) */
export function setupLink(b: TodayBiz, key: string): string {
  // অনুমোদন Lifolo টিমের কাজ — নিজের কিছু করার নেই (চাপা যায় না)
  if (key === 'approved' || key === 'approval') return '';
  if (key === 'base') return `/work/ambulance`;
  if (key === 'homeVisit') return '/work/pro/chamber';
  switch (b.kind) {
    case 'chamber':
    case 'homecare':
      return '/work/pro/chamber';
    case 'pharmacy':
      return '/work/pharmacy/edit';
    case 'lab':
      return key === 'prices' ? '/work/lab/orders/prices' : '/work/lab/edit';
    case 'bloodbank':
      return key === 'stock' ? '/work/bloodbank' : `/work/bloodbank/${b.id}`;
    default:
      return `/work/ambulance/${b.id}`;
  }
}

/** ভূমিকার "শুরু করতে যা বাকি" — সব ব্যবসা মিলিয়ে (একই চাবি একবার; সবগুলোয় হলে তবেই ✓); সব হয়ে গেলে খালি */
export function roleSetup(bizs: TodayBiz[]): { key: string; done: boolean; link: string }[] {
  const m = new Map<string, { key: string; done: boolean; link: string }>();
  for (const b of bizs) for (const s of b.setup) {
    const old = m.get(s.key);
    if (!old) m.set(s.key, { key: s.key, done: s.done, link: setupLink(b, s.key) });
    else if (!s.done && old.done) m.set(s.key, { key: s.key, done: false, link: setupLink(b, s.key) });
  }
  const xs = [...m.values()];
  return xs.some((x) => !x.done) ? xs : [];
}

/** "এখনই দেখুন": সবচেয়ে নতুন আগে (সময় না থাকলে শেষে) */
export function sortTasks(t: TodayTask[]) {
  return [...t].sort((a, b) => (b.at || 0) - (a.at || 0));
}

// ---------- অবস্থা বদল (খোলা / বিরতি / বন্ধ) ----------
export type PauseFor = 30 | 60 | 'day';
const DHAKA = 6 * 3600_000;
export function nextDhakaMidnight(now = Date.now()) {
  const d = new Date(now + DHAKA);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1) - DHAKA;
}
export const pauseUntil = (p: PauseFor, now = Date.now()) => (p === 'day' ? nextDhakaMidnight(now) : now + p * 60_000);

/**
 * সার্ভারের কাজ — mywork CONTRACT "## Changes"-এ backend যে নাম দেবে সেটা; এখন listings-এর নাম (pharmacy_open_override,
 * lab_open_override, chamber_day_set, ambulance_available), বিরতিতে বাড়তি `until` (ms)।
 */
export function statusCall(b: TodayBiz, to: Status, pause?: PauseFor): [string, Record<string, unknown>] {
  const until = to === 'paused' ? pauseUntil(pause ?? 30) : undefined;
  switch (b.kind) {
    case 'pharmacy':
    case 'lab':
      return [`${b.kind}_open_override`, to === 'open' ? { open: true } : { open: false, ...(until ? { until } : {}) }];
    case 'chamber':
      return ['chamber_day_set', {
        ...(b.doctorUid ? { doctorUid: b.doctorUid } : {}),
        chamberIndex: b.chamberIndex ?? 0,
        ...(b.chamberKey ? { chamberKey: b.chamberKey } : {}),
        closed: to !== 'open',
        ...(until ? { until } : {}),
      }];
    case 'ambulance':
      return ['ambulance_available', { id: b.id, available: to === 'open' }];
    default:
      throw new Error('no status for ' + b.kind);
  }
}
/** ব্লাড ব্যাংক আর বাসায় সেবায় খোলা/বন্ধ নেই; অ্যাম্বুলেন্সে বিরতি নেই */
export const hasStatus = (b: TodayBiz) => !b.listing && !b.noToday && (b.kind === 'pharmacy' || b.kind === 'lab' || b.kind === 'chamber' || b.kind === 'ambulance');
export const hasPause = (b: TodayBiz) => b.kind !== 'ambulance';

// ---------- আগের কাজ থেকে একই আকার (সার্ভারে work_today না থাকলে) ----------
type Caller = <T = any>(action: string, body?: Record<string, unknown>) => Promise<T>;
const safe = async <T,>(p: Promise<T>): Promise<T | null> => {
  try {
    return await p;
  } catch {
    return null;
  }
};
interface DayItem { chamberIndex: number; chamberKey?: string; name?: string; nameBn?: string; appointments?: boolean; sitsToday?: boolean; closedToday?: boolean }
interface QRow { id: string; serial: number; name: string; status?: string; state?: string }
interface QChamber { chamberKey?: string; key?: string; name?: string; nameBn?: string; rows?: QRow[] }

function chamberBizs(days: { items?: DayItem[] } | null, queue: { chambers?: QChamber[] } | null, opts: { doctorUid?: string | null; who?: string; bn: boolean; fallbackName: string }): TodayBiz[] {
  const today = (days?.items ?? []).filter((d) => d.sitsToday && d.appointments !== false);
  const qs = queue?.chambers ?? [];
  const pre = opts.who ? `${opts.who} · ` : '';
  const base = { kind: 'chamber' as const, pausedUntil: null, setup: [], listing: null, doctorUid: opts.doctorUid ?? null };
  if (!today.length) return [{ ...base, id: `${opts.doctorUid ?? 'me'}:none`, name: pre + opts.fallbackName, status: null, counts: {}, tasks: [], noToday: true }];
  return today.map((d, i): TodayBiz => {
    const nm = (opts.bn && d.nameBn) || d.name || opts.fallbackName;
    const q = qs.find((c) => (d.chamberKey && (c.chamberKey === d.chamberKey || c.key === d.chamberKey)) || (d.name && c.name === d.name)) ?? (today.length === qs.length ? qs[i] : undefined);
    const rows = (q?.rows ?? []).filter((r) => r.status !== 'cancelled' && r.state !== 'cancelled');
    const waiting = rows.filter((r) => r.state === 'waiting' || r.state === 'later');
    const link = opts.doctorUid ? `/work/rx?doctor=${encodeURIComponent(opts.doctorUid)}` : '/work/pro?v=active';
    return {
      ...base,
      id: `${opts.doctorUid ?? 'me'}:${d.chamberIndex}`,
      name: pre + nm,
      status: d.closedToday ? 'closed' : 'open',
      chamberIndex: d.chamberIndex,
      ...(d.chamberKey ? { chamberKey: d.chamberKey } : {}),
      counts: (q ? { today: rows.length, waiting: waiting.length } : {}) as Record<string, number>,
      tasks: waiting.slice(0, 2).map((r) => ({ kind: 'chamber' as const, id: `q:${r.id}`, title: r.name, sub: `${TT.serialOf(r.serial)} · ${nm}`, at: 0, action: 'call', link })),
    };
  });
}

const openStatus = (x: { openNow?: boolean | null }): Status => (x.openNow === false ? 'closed' : 'open');
const docsSetup = (x: { docsMissing?: boolean; docsRequested?: boolean; status?: string }): SetupItem[] =>
  x.status === 'pending' || x.docsMissing || x.docsRequested ? [{ key: 'docs', done: !(x.docsMissing || x.docsRequested) }, { key: 'approved', done: x.status === 'approved' }] : [];

export async function buildFallback(w: WorkSummary, opts: { uid: string | null; bn: boolean; call?: Caller; now?: number }): Promise<TodayBiz[]> {
  const c: Caller = opts.call ?? (call as Caller);
  const now = opts.now ?? Date.now();
  const out: TodayBiz[] = [];
  const ph = w.pharmacy as any;
  const lab = w.lab as any;
  const own = isChamberType(w.professional);
  const docs = w.assisting.slice(0, 3);
  const [days, queue, phOrders, sales, finds, labOrders, calls, ...assist] = await Promise.all([
    own ? safe(c('chamber_days', {})) : null,
    own ? safe(c('queue_today', {})) : null,
    ph?.status === 'approved' ? safe(c('rx_orders_pharmacy', {})) : null,
    ph?.status === 'approved' ? safe(c('books_sales', { period: 'today' })) : null,
    ph?.status === 'approved' ? safe(c('med_find_requests', {})) : null,
    lab?.status === 'approved' ? safe(c('lab_orders', {})) : null,
    w.ambulances.some((a: any) => a.status === 'approved') ? safe(c('ambreq_driver', {})) : null,
    ...docs.flatMap((d) => [safe(c('chamber_days', { doctorUid: d.doctorUid })), safe(c('queue_today', { doctorUid: d.doctorUid }))]),
  ]);

  if (own) out.push(...chamberBizs(days, queue, { bn: opts.bn, fallbackName: TT.chamber() }));
  if (w.professional === 'nurse') out.push({ kind: 'homecare', id: 'homecare', name: TT.homecare(), status: null, pausedUntil: null, counts: {}, tasks: [], setup: [], listing: null });
  docs.forEach((d, i) => out.push(...chamberBizs(assist[i * 2] as any, assist[i * 2 + 1] as any, { doctorUid: d.doctorUid, who: d.name, bn: opts.bn, fallbackName: TT.chamber() })));

  if (ph) {
    const approved = ph.status === 'approved';
    const items: any[] = (phOrders as any)?.items ?? [];
    const fresh = items.filter((o) => (o.seg ? o.seg === 'new' : o.status === 'sent'));
    const counts: Record<string, number> = {};
    if (phOrders) {
      counts.new = (phOrders as any).counts?.new ?? fresh.length;
      counts.active = (phOrders as any).counts?.active ?? items.filter((o) => ['ready', 'accepted', 'out'].includes(o.status)).length;
    }
    if (sales) counts.salesTodayTk = ((sales as any).items ?? []).reduce((a: number, s: any) => a + (Number(s?.totals?.net) || 0), 0);
    const open = finds && !(finds as any).findOff ? ((finds as any).items ?? []).filter((r: any) => !r.answer).length : null;
    if (open != null) counts.finds = open;
    const tasks: TodayTask[] = fresh.map((o) => ({ kind: 'pharmacy', id: `o:${o.id}`, title: TT.rxOrderOf(str(o.patientName)), sub: str(o.code), at: o.createdAt || 0, action: 'accept', link: `/work/pharmacy/orders/rx/${o.code}` }));
    tasks.push(...items.filter((o) => o.status === 'accepted').map((o) => ({ kind: 'pharmacy' as const, id: `o:${o.id}`, title: TT.rxOrderOf(str(o.patientName)), sub: str(o.code), at: o.createdAt || 0, action: 'ready', link: '/work/pharmacy/orders?s=active' })));
    if (open) tasks.push({ kind: 'pharmacy', id: 'finds', title: TT.findsTask(open), sub: '', at: 0, action: 'answer', link: '/work/pharmacy/finds' });
    out.push({ kind: 'pharmacy', id: str(ph.id) || 'pharmacy', name: str(ph.name), status: approved ? openStatus(ph) : null, pausedUntil: null, counts, tasks, setup: docsSetup(ph), listing: approved ? null : str(ph.status) });
  }

  if (lab) {
    const approved = lab.status === 'approved';
    const items: any[] = (labOrders as any)?.items ?? [];
    const fresh = items.filter((o) => (o.seg ? o.seg === 'new' : o.status === 'sent'));
    const due = items.filter((o) => (o.seg ? o.seg === 'active' : o.status === 'received'));
    const counts: Record<string, number> = labOrders ? { new: (labOrders as any).counts?.new ?? fresh.length, active: (labOrders as any).counts?.active ?? due.length } : {};
    const tasks: TodayTask[] = [
      ...fresh.map((o) => ({ kind: 'lab' as const, id: `l:${o.id}`, title: str(o.patientName), sub: (o.tests ?? []).join(', '), at: o.createdAt || 0, action: 'accept', link: `/work/lab/orders/${o.id}` })),
      ...due.map((o) => ({ kind: 'lab' as const, id: `l:${o.id}`, title: str(o.patientName), sub: (o.tests ?? []).join(', '), at: o.createdAt || 0, action: 'report', link: `/work/lab/orders/${o.id}` })),
    ];
    out.push({ kind: 'lab', id: str(lab.id) || 'lab', name: str(lab.name), status: approved ? openStatus(lab) : null, pausedUntil: null, counts, tasks, setup: docsSetup(lab), listing: approved ? null : str(lab.status) });
  }

  for (const b of w.bloodBanks as any[]) {
    const approved = b.status === 'approved';
    const stale = !b.stock || b.stockStale === true;
    out.push({
      kind: 'bloodbank', id: str(b.id), name: str(b.name), status: null, pausedUntil: null, counts: {},
      tasks: approved && stale ? [{ kind: 'bloodbank', id: `s:${b.id}`, title: TT.stockTask(), sub: str(b.name), at: 0, action: 'stock', link: '/work/bloodbank' }] : [],
      setup: docsSetup(b), listing: approved ? null : str(b.status), stockUpdatedAt: numOr(b.stockUpdatedAt), stockStale: b.stockStale === true,
    });
  }

  const callItems: any[] = ((calls as any)?.items ?? []).filter((r: any) => r.status === 'open');
  (w.ambulances as any[]).forEach((a, i) => {
    const approved = a.status === 'approved';
    const first = approved && !out.some((x) => x.kind === 'ambulance' && !x.listing);
    out.push({
      kind: 'ambulance', id: str(a.id), name: str(a.vehicleNo || a.name), status: approved ? (a.available ? 'open' : 'closed') : null, pausedUntil: null,
      counts: first && calls ? { calls: callItems.length } : {},
      tasks: first ? callItems.map((r) => ({ kind: 'ambulance' as const, id: `c:${r.id}`, title: TT.callFrom(str(r.pickup)), sub: str(r.patientName), at: r.createdAt || 0, action: 'open', link: '/work/ambulance' })) : [],
      setup: docsSetup(a), listing: approved ? null : str(a.status),
    });
    void i;
  });
  void now;
  return out;
}

// ---------- পাতার জন্য ----------
export function useWorkToday() {
  const s = useSession();
  const enabled = computed(() => s.ready && !s.guest && s.workLoaded);
  const q = useQ<Today>(() => ({
    queryKey: ['work_today', authUid.value, getLang()],
    queryFn: async () => {
      try {
        return { items: normToday(await call('work_today', {}), { bn: getLang() === 'bn' }), source: 'server', at: Date.now() };
      } catch (e) {
        if (!isMissing(e)) throw e;
        return { items: await buildFallback(s.work, { uid: s.uid, bn: getLang() === 'bn' }), source: 'fallback', at: Date.now() };
      }
    },
    enabled: enabled.value,
    refetchInterval: 60_000,
    staleTime: 15_000,
    retry: 1,
  }));
  return q;
}

/** কোন ভূমিকা দেখানো হচ্ছে ("সব" বা একটা) — ব্যবহারকারী ধরে এই ব্রাউজারে মনে থাকে */
export function useRoleChoice() {
  const s = useSession();
  const key = computed(() => `lf.work.role.${s.uid ?? 'guest'}`);
  const v = ref<Role | 'all'>((store.get(key.value) as Role | 'all' | null) ?? 'all');
  watch(key, (k) => (v.value = (store.get(k) as Role | 'all' | null) ?? 'all'));
  return {
    value: v,
    set(r: Role | 'all') {
      v.value = r;
      store.set(key.value, r === 'all' ? null : r);
    },
  };
}
