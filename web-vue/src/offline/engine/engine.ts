// অফলাইন ইঞ্জিন (ডেস্কটপ): ক্যাশ থেকে পড়া, কাজ (op) সাথে সাথে এখানে লেখা আর রসিদ, পেছনে সিঙ্ক।
//
// নকশা:
//  • `ops` টেবিল কাজের খাতা — একটা INSERT-ই একটা কাজের "সত্য" (plugin-sql-এ লেনদেন নেই)। রসিদের টেবিল খাতা থেকে আবার বানানো যায়।
//  • সার্ভারের অবস্থা (items.stock, appts) আলাদা থাকে; সিঙ্ক না হওয়া কাজের প্রভাব (effects) পড়ার সময় তার ওপর বসে।
//    সার্ভার কাজটা নিলে: delta-য় সেই জিনিস এলে সার্ভারের মান, না এলে প্রভাবটা স্থায়ী টেবিলে মেশে। না নিলে প্রভাব বাদ।
//  • রসিদের নম্বর এই কম্পিউটারের সিরিজে (D1-000001 …), যাচাই কোড এখানেই (CSPRNG), সার্ভার নম্বর বদলালে আগেরটা deviceNo।
//  • ব্যক্তিগত অংশ (রসিদ, কাজের data, রোগীর নাম) AES-GCM-এ সিল করা (crypt.ts), চাবি DPAPI-তে।
//  • সিঙ্ক: ক্রমে, ১০০টা করে; ব্যর্থ হলে পিছিয়ে (২ সে → ৫ মিনিট পর্যন্ত); অনলাইনে প্রতি মিনিটে একবার delta টানা।
import { T } from '../text';
import { OfflineError } from '../types';
import type {
  BizKind,
  ChamberAppt,
  ChamberDay,
  DeskRole,
  Invoice,
  InvoiceQuery,
  LabPrices,
  OfflineItem,
  OfflineOp,
  OfflineStatus,
  OfflineStore,
  OpType,
  PayData,
  PrintOptions,
  ReadyInfo,
  RecordResult,
  VoidData,
} from '../types';
import { applyPay, applyVoid, buildFee, buildLabBill, buildSale, discountShare, type Effect, type IssueCtx } from './build';
import type { Cipher } from './crypt';
import { getMeta, migrate, searchText, setMeta, type Db } from './db';
import { parseSnapshot, type Snapshot } from './parse';

export interface DeviceCred {
  deviceId: string;
  series: string;
  secret: string;
}
export interface SyncOpWire {
  opId: string;
  type: OpType;
  at: number;
  data: Record<string, unknown>;
}
export interface SyncResult {
  opId: string;
  ok: boolean;
  id?: string;
  no?: string;
  warn?: string;
  error?: string;
}

/** সার্ভারের ভুল: status (0 = নেট নেই) আর code (device_revoked …) */
export interface ApiError {
  status?: number;
  code?: string;
  message?: string;
}

export interface EngineDeps {
  openDb(name: string): Promise<Db>;
  /** ডেটাবেস ফাইল মুছে ফেলা (খোলা থাকলে বন্ধ করে) */
  dropDb(name: string): Promise<void>;
  cipher(): Promise<Cipher>;
  secrets: { get(name: string): Promise<string | null>; set(name: string, v: string): Promise<void>; del(name: string): Promise<void> };
  api: {
    register(kind: BizKind, bizId: string, name: string): Promise<{ deviceId: string; series: string; secret?: string }>;
    bootstrap(dev: DeviceCred): Promise<unknown>;
    sync(dev: DeviceCred, ops: SyncOpWire[], asOf: number): Promise<{ results?: SyncResult[]; delta?: unknown }>;
  };
  uid(): string | null;
  deviceName(): Promise<string>;
  now(): number;
  uuid(): string;
  randomCode(len: number): string;
  online(): boolean;
  /** লক/৭ দিন/ঘড়ি — 'ok' না হলে নতুন কাজ নয় */
  gate(): 'ok' | 'locked' | 'login_expired' | 'clock';
  role(): DeskRole | null;
  /** মালিক নিজে, বা এইমাত্র মালিকের পিন দেওয়া (requireOwner) */
  canOwner(): boolean;
  requireOwner(): Promise<boolean>;
  print(inv: Invoice, opts: PrintOptions): Promise<void>;
  /** bootstrap-এ নাম না এলে সেশনের তথ্য (আমার কাজ) */
  bizInfo(kind: BizKind, bizId: string): { name: string; phone?: string; address?: string } | null;
  setTimer(fn: () => void, ms: number): unknown;
  clearTimer(h: unknown): void;
  /** নিবন্ধন হলো (ফিচার সুইচ বন্ধ হলেও এই কম্পিউটার সিঙ্ক চালিয়ে যায়) / ফিচার বন্ধ বলে হলো না */
  onRegistered?(registered: boolean): void;
  /** এই ব্যবসার অফলাইন তথ্য মুছে গেল (device_revoked) */
  onWiped?(why: 'revoked'): void;
}

interface OpRow {
  opId: string;
  seq: number;
  type: OpType;
  at: number;
  data: Record<string, any>;
  effects: Effect[];
  localId: string;
  no: string;
  invoiceKey: string | null;
  tries: number;
}

const OP_TYPES_BY_KIND: Record<BizKind, OpType[]> = {
  pharmacy: ['sale', 'purchase', 'adjust', 'pay', 'void'],
  lab: ['lab_bill', 'pay', 'void'],
  chamber: ['fee', 'walkin', 'checkin', 'pay', 'void'],
};
const BATCH = 100;
const BACKOFF = [2_000, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000];
const PULL_MS = 60_000;
const DAY = 86_400_000;
/** সিঙ্ক হওয়া রসিদ আর কাজ এত দিন পর এই কম্পিউটার থেকে মোছা (I4.5) */
const KEEP_MS = 30 * DAY;
const DEV_WIRE_FIELDS = ['invoice'];

function err(code: OfflineError['code']): OfflineError {
  const msg: Record<string, () => string> = {
    not_ready: T.notReady,
    need_online: T.needOnline,
    login_expired: T.loginExpired,
    device_revoked: T.revoked,
    locked: T.locked,
    owner_only: T.ownerOnly,
    clock: T.clockWrong,
    feature_off: T.featureOff,
    invalid: T.invalid,
  };
  return new OfflineError((msg[code] ?? T.invalid)(), code);
}

const pos = (n: unknown) => typeof n === 'number' && Number.isFinite(n) && n > 0;
const nonneg = (n: unknown) => typeof n === 'number' && Number.isFinite(n) && n >= 0;

export class OfflineEngine implements OfflineStore {
  private db: Db | null = null;
  private cipher: Cipher | null = null;
  private scope: { kind: BizKind; bizId: string; uid: string; name: string } | null = null;
  private dev: DeviceCred | null = null;
  private biz: { name: string; phone?: string; address?: string } = { name: '' };
  private demo = false;
  private asOf = 0;
  private seqFloor = 0;
  private pendingOps: OpRow[] = [];
  private nextSeq = 1;
  private visitFees: ChamberDay['fees'] = {};
  private labFees: LabPrices['fees'] = [];
  private chambers: ChamberDay['chambers'] = [];
  private status: OfflineStatus = { online: false, syncing: false, pending: 0, rejected: 0, lastSyncAt: 0, revoked: false };
  private listeners = new Set<(s: OfflineStatus) => void>();
  private lock: Promise<unknown> = Promise.resolve();
  private timer: unknown = null;
  private fails = 0;
  private opening: Promise<ReadyInfo> | null = null;
  private stopped = false;

  constructor(private d: EngineDeps) {}

  // ---------------------------------------------------------------- OfflineStore
  isDesktop() {
    return true;
  }

  role() {
    return this.d.role();
  }

  requireOwner() {
    return this.d.requireOwner();
  }

  pending() {
    return this.pendingOps.length;
  }

  onChange(cb: (s: OfflineStatus) => void) {
    this.listeners.add(cb);
    cb({ ...this.status });
    return () => {
      this.listeners.delete(cb);
    };
  }

  print(invoice: Invoice, opts: PrintOptions) {
    return this.d.print(invoice, opts);
  }

  async ready(kind: BizKind, bizId: string): Promise<ReadyInfo> {
    const uid = this.d.uid();
    if (!uid) throw err('need_online');
    if (!OP_TYPES_BY_KIND[kind] || !bizId) throw err('invalid');
    const name = `offline-${hashName(`${uid}|${kind}|${bizId}`)}`;
    if (this.scope?.name === name && this.dev && this.opening) return this.opening;
    this.opening = this.open(kind, bizId, uid, name);
    try {
      return await this.opening;
    } catch (e) {
      this.opening = null;
      throw e;
    }
  }

  async items(q = ''): Promise<OfflineItem[]> {
    const db = this.need();
    const s = searchText(q);
    const rows = s
      ? await db.select<{ json: string; stock: number }>(
          `SELECT json, stock FROM items WHERE search LIKE ? ESCAPE '\\' OR code = ? ORDER BY CASE WHEN code = ? THEN 0 WHEN search LIKE ? ESCAPE '\\' THEN 1 ELSE 2 END, name LIMIT 50`,
          [`%${likeEsc(s)}%`, q.trim(), q.trim(), `${likeEsc(s)}%`],
        )
      : await db.select<{ json: string; stock: number }>(`SELECT json, stock FROM items ORDER BY name LIMIT 200`);
    const adj = this.stockAdj();
    return rows.map((r) => {
      const it = JSON.parse(r.json) as OfflineItem;
      it.stock = round3(Number(r.stock) + (adj.get(it.id) ?? 0));
      return it;
    });
  }

  async labPrices(): Promise<LabPrices> {
    const db = this.need();
    const rows = await db.select<{ json: string }>(`SELECT json FROM lab_prices ORDER BY name`);
    return { items: rows.map((r) => JSON.parse(r.json)), fees: this.labFees.map((f) => ({ ...f })) };
  }

  async chamberDay(date: string): Promise<ChamberDay> {
    const db = this.need();
    const rows = await db.select<{ json: string }>(`SELECT json FROM appts WHERE date = ?`, [date]);
    const map = new Map<string, ChamberAppt>();
    for (const r of rows) {
      const a = await this.open_<ChamberAppt>(r.json);
      map.set(a.id, a);
    }
    for (const op of this.pendingOps) applyApptEffects(map, op.effects, date);
    const list = [...map.values()].sort((a, b) => (a.serial ?? 1e9) - (b.serial ?? 1e9) || a.name.localeCompare(b.name));
    // চেম্বারের তালিকায় নেই এমন সারির চেম্বারও (নাম = key)
    const chambers = this.chambers.map((c) => ({ ...c }));
    for (const a of list) if (a.chamberKey && !chambers.some((c) => c.chamberKey === a.chamberKey)) chambers.push({ chamberKey: a.chamberKey, name: a.chamberKey });
    return { date, chambers, appointments: list, fees: { ...this.visitFees } };
  }

  async record(op: OfflineOp): Promise<RecordResult> {
    return this.serial(() => this.record_(op));
  }

  async syncNow(): Promise<void> {
    if (this.timer) this.d.clearTimer(this.timer);
    this.timer = null;
    await this.syncLoop(true);
  }

  // ---------------------------------------------------------------- বাড়তি (ডেস্কটপ সেটিংস)

  /** সার্ভার নেয়নি এমন রসিদ/কাজ */
  async rejectedOps(): Promise<{ opId: string; type: OpType; no: string; error: string; at: number }[]> {
    const db = this.db;
    if (!db) return [];
    const rows = await db.select<{ opId: string; type: OpType; no: string; result: string; at: number }>(
      `SELECT opId, type, no, result, at FROM ops WHERE state = 'rejected' ORDER BY seq DESC LIMIT 100`,
    );
    return rows.map((r) => ({ opId: r.opId, type: r.type, no: r.no, at: r.at, error: safeJson(r.result)?.error ?? '' }));
  }

  /** রসিদের তালিকা (অফলাইনেও): সময় (ঢাকার দিন ধরে) আর খোঁজা — নম্বর, ছাপা নম্বর, গ্রাহকের নাম, জিনিসের নাম */
  async invoices(query: InvoiceQuery = {}): Promise<Invoice[]> {
    const db = this.db;
    if (!db) return [];
    const since = periodStart(query.period, this.d.now());
    const rows = await db.select<{ json: string }>(`SELECT json FROM invoices WHERE issuedAt >= ? ORDER BY issuedAt DESC LIMIT 2000`, [since]);
    const q = (query.q ?? '').trim().toLowerCase();
    const out: Invoice[] = [];
    for (const r of rows) {
      const inv = await this.open_<Invoice>(r.json);
      if (inv.bizId && this.scope && inv.bizId !== this.scope.bizId) continue;
      if (q) {
        const hay = [inv.no, inv.deviceNo, inv.customer?.name, ...inv.lines.map((l) => l.name)].filter(Boolean).join(' ').toLowerCase();
        if (!hay.includes(q)) continue;
      }
      out.push(inv);
      if (out.length >= (query.limit ?? 500)) break;
    }
    return out;
  }

  /** এই কম্পিউটারে রাখা রসিদ (নতুন আগে) */
  async localInvoices(limit = 100): Promise<Invoice[]> {
    const db = this.db;
    if (!db) return [];
    const rows = await db.select<{ json: string }>(`SELECT json FROM invoices ORDER BY issuedAt DESC LIMIT ?`, [limit]);
    const out: Invoice[] = [];
    for (const r of rows) out.push(await this.open_<Invoice>(r.json));
    return out;
  }

  device(): (DeviceCred & { kind: BizKind; bizId: string; asOf: number }) | null {
    return this.dev && this.scope ? { ...this.dev, secret: '', kind: this.scope.kind, bizId: this.scope.bizId, asOf: this.asOf } : null;
  }

  /** লগআউট/অন্য অ্যাকাউন্ট: টাইমার বন্ধ, ডেটাবেস বন্ধ (মোছা নয় — আবার লগইনে সিঙ্ক চলবে) */
  async close(): Promise<void> {
    this.stopped = true;
    if (this.timer) this.d.clearTimer(this.timer);
    this.timer = null;
    await this.lock.catch(() => {});
    await this.db?.close?.();
    this.db = null;
    this.scope = null;
    this.dev = null;
    this.opening = null;
    this.pendingOps = [];
    this.emit({ pending: 0, rejected: 0, syncing: false });
  }

  // ---------------------------------------------------------------- খোলা

  private async open(kind: BizKind, bizId: string, uid: string, name: string): Promise<ReadyInfo> {
    if (this.scope && this.scope.name !== name) await this.close();
    this.stopped = false;
    this.cipher ??= await this.d.cipher();
    const db = await this.d.openDb(name);
    await migrate(db);
    this.db = db;
    this.scope = { kind, bizId, uid, name };

    const secretName = `dev-${name}`;
    let devJson = await this.d.secrets.get(secretName);
    if (!devJson) {
      if (!this.d.online()) throw err('need_online');
      const pcName = await this.d.deviceName();
      let r: { deviceId: string; series: string; secret?: string };
      try {
        r = await this.d.api.register(kind, bizId, pcName);
      } catch (e) {
        const oe = this.apiToOffline(e);
        if (oe.code === 'feature_off') this.d.onRegistered?.(false);
        throw oe;
      }
      if (!r?.deviceId || !r.series) throw err('invalid');
      this.d.onRegistered?.(true);
      devJson = JSON.stringify({ deviceId: String(r.deviceId), series: String(r.series), secret: String(r.secret ?? '') });
      await this.d.secrets.set(secretName, devJson);
    }
    this.dev = JSON.parse(devJson) as DeviceCred;
    this.status.revoked = false;

    const m = await getMeta(db, 'state');
    const st = m ? (safeJson(m) ?? {}) : {};
    this.biz = st.biz ?? this.d.bizInfo(kind, bizId) ?? { name: '' };
    this.demo = !!st.demo;
    this.asOf = Number(st.asOf) || 0;
    this.seqFloor = Number(st.seqFloor) || 0;
    this.visitFees = st.visitFees ?? {};
    this.labFees = st.labFees ?? [];
    this.chambers = st.chambers ?? [];

    await this.loadPending();
    await this.repair();
    await this.purge();
    await this.countRejected();

    const fresh = (await getMeta(db, 'bootstrapOk')) === '1';
    if (!fresh) {
      if (!this.d.online()) {
        if (!this.asOf) throw err('need_online');
      } else {
        try {
          await this.bootstrap();
        } catch (e) {
          const oe = this.apiToOffline(e);
          if (oe.code === 'device_revoked' || !this.asOf) throw oe;
        }
      }
    }
    this.emit({ pending: this.pendingOps.length });
    this.schedule(this.pendingOps.length ? 300 : 2_000);
    return { deviceId: this.dev.deviceId, series: this.dev.series, asOf: this.asOf, pending: this.pendingOps.length };
  }

  private async bootstrap(): Promise<void> {
    const db = this.need();
    const raw = await this.d.api.bootstrap(this.dev!);
    const snap = parseSnapshot(raw, this.dev!.series);
    await setMeta(db, 'bootstrapOk', '0');
    if (snap.items) {
      await db.execute(`DELETE FROM items`);
      await this.upsertItems(snap.items);
    }
    if (snap.labPrices) {
      await db.execute(`DELETE FROM lab_prices`);
      await this.upsertLab(snap);
    }
    if (snap.appts) {
      await db.execute(`DELETE FROM appts`);
      await this.upsertAppts(snap.appts);
    }
    await this.saveState(snap, true);
    await setMeta(db, 'bootstrapOk', '1');
  }

  private async saveState(snap: Snapshot, full: boolean) {
    if (snap.biz) this.biz = snap.biz;
    if (full || snap.demo) this.demo = snap.demo;
    if (snap.asOf) this.asOf = snap.asOf;
    else if (full) this.asOf = this.d.now();
    this.seqFloor = Math.max(this.seqFloor, snap.seqFloor);
    if (snap.visitFees) this.visitFees = snap.visitFees;
    if (snap.labFees) this.labFees = snap.labFees;
    if (snap.chambers) this.chambers = snap.chambers;
    this.nextSeq = Math.max(this.nextSeq, this.seqFloor + 1);
    await setMeta(
      this.need(),
      'state',
      JSON.stringify({
        biz: this.biz,
        demo: this.demo,
        asOf: this.asOf,
        seqFloor: this.seqFloor,
        visitFees: this.visitFees,
        labFees: this.labFees,
        chambers: this.chambers,
      }),
    );
  }

  private async upsertItems(items: OfflineItem[]) {
    const db = this.need();
    for (let i = 0; i < items.length; i += 150) {
      const part = items.slice(i, i + 150);
      const params: unknown[] = [];
      for (const it of part) params.push(it.id, it.name, it.code ?? null, searchText(it.name, it.generic, it.code, it.strength), it.stock, JSON.stringify(it));
      await db.execute(
        `INSERT INTO items (id, name, code, search, stock, json) VALUES ${part.map(() => '(?, ?, ?, ?, ?, ?)').join(', ')}
         ON CONFLICT(id) DO UPDATE SET name = excluded.name, code = excluded.code, search = excluded.search, stock = excluded.stock, json = excluded.json`,
        params,
      );
    }
  }

  private async upsertLab(snap: Snapshot) {
    const db = this.need();
    const list = snap.labPrices ?? [];
    for (let i = 0; i < list.length; i += 200) {
      const part = list.slice(i, i + 200);
      const params: unknown[] = [];
      for (const p of part) params.push(p.id, p.name, JSON.stringify(p));
      await db.execute(
        `INSERT INTO lab_prices (id, name, json) VALUES ${part.map(() => '(?, ?, ?)').join(', ')}
         ON CONFLICT(id) DO UPDATE SET name = excluded.name, json = excluded.json`,
        params,
      );
    }
  }

  private async upsertAppts(list: ChamberAppt[]) {
    const db = this.need();
    for (const a of list) {
      await db.execute(`INSERT INTO appts (id, date, json) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET date = excluded.date, json = excluded.json`, [
        a.id,
        a.date,
        await this.seal(a),
      ]);
    }
  }

  private async loadPending() {
    const rows = await this.need().select<{ opId: string; seq: number; type: OpType; at: number; data: string; effects: string; localId: string; no: string; invoiceKey: string | null; tries: number }>(
      `SELECT opId, seq, type, at, data, effects, localId, no, invoiceKey, tries FROM ops WHERE state = 'pending' ORDER BY seq`,
    );
    this.pendingOps = [];
    for (const r of rows) {
      this.pendingOps.push({ ...r, seq: Number(r.seq), at: Number(r.at), tries: Number(r.tries), data: await this.open_(r.data), effects: await this.open_(r.effects) });
    }
    const mx = await this.need().select<{ s: number | null }>(`SELECT MAX(seq) AS s FROM ops`);
    this.nextSeq = Math.max(1, Number(mx[0]?.s ?? 0) + 1, this.seqFloor + 1);
  }

  /** কাজ লেখা হয়েছে কিন্তু রসিদের সারি নেই (মাঝপথে বন্ধ) — খাতা থেকে আবার বানানো */
  private async repair() {
    const db = this.need();
    const rows = await db.select<{ opId: string; data: string; invoiceKey: string }>(
      `SELECT o.opId, o.data, o.invoiceKey FROM ops o LEFT JOIN invoices i ON i.key = o.invoiceKey
       WHERE o.invoiceKey IS NOT NULL AND o.type IN ('sale', 'lab_bill', 'fee') AND i.key IS NULL`,
    );
    for (const r of rows) {
      const data = await this.open_<Record<string, any>>(r.data);
      const inv = data._invoice as Invoice | undefined;
      if (inv) await this.putInvoice(r.invoiceKey, inv);
    }
  }

  /** ৩০ দিনের পুরোনো, সিঙ্ক হওয়া রসিদ আর কাজ মোছা (ফোন নম্বরসহ) */
  private async purge() {
    const db = this.need();
    const cut = this.d.now() - KEEP_MS;
    await db.execute(`DELETE FROM ops WHERE state = 'done' AND COALESCE(doneAt, at) < ?`, [cut]);
    await db.execute(`DELETE FROM invoices WHERE issuedAt < ? AND key NOT IN (SELECT invoiceKey FROM ops WHERE invoiceKey IS NOT NULL AND state != 'done')`, [cut]);
    await db.execute(`DELETE FROM appts WHERE date < ?`, [new Date(this.d.now() - 2 * DAY).toISOString().slice(0, 10)]);
  }

  private async countRejected() {
    const r = await this.need().select<{ n: number }>(`SELECT COUNT(*) AS n FROM ops WHERE state = 'rejected'`);
    this.status.rejected = Number(r[0]?.n ?? 0);
  }

  // ---------------------------------------------------------------- কাজ লেখা

  private async record_(op: OfflineOp): Promise<RecordResult> {
    const db = this.need();
    if (!this.dev || !this.scope) throw err('not_ready');
    if (this.status.revoked) throw err('device_revoked');
    const g = this.d.gate();
    if (g !== 'ok') throw err(g);
    if (!op || !OP_TYPES_BY_KIND[this.scope.kind].includes(op.type)) throw err('invalid');

    const at = this.d.now();
    const opId = this.d.uuid();
    const localId = this.d.uuid();
    let effects: Effect[] = [];
    let invoice: Invoice | null = null;
    let invoiceKey: string | null = null;
    let no = '';
    const data: Record<string, any> = { ...(op.data as any), localId };

    const issue = (): IssueCtx => {
      no = `${this.dev!.series}-${String(this.nextSeq).padStart(6, '0')}`;
      const code = this.demo ? 'DEMO' + this.d.randomCode(6) : this.d.randomCode(10);
      return {
        kind: this.scope!.kind,
        bizId: this.scope!.bizId,
        biz: this.biz.name ? this.biz : (this.d.bizInfo(this.scope!.kind, this.scope!.bizId) ?? { name: '' }),
        deviceId: this.dev!.deviceId,
        uid: this.scope!.uid,
        localId,
        no,
        verifyCode: code,
        at,
        demo: this.demo,
      };
    };

    switch (op.type) {
      case 'sale': {
        const d = op.data;
        if (!Array.isArray(d?.lines) || !d.lines.length || d.lines.some((l) => !l?.name?.trim() || !pos(l.qty) || !nonneg(l.price) || (l.disc != null && !nonneg(l.disc))))
          throw err('invalid');
        await this.checkPrices(d.lines, d.disc);
        const r = buildSale(d, issue());
        invoice = r.invoice;
        effects = r.effects;
        break;
      }
      case 'lab_bill': {
        const d = op.data;
        if (!Array.isArray(d?.lines) || !d.lines.length || d.lines.some((l) => !l?.name?.trim() || !nonneg(l.price) || (l.qty != null && !pos(l.qty)))) throw err('invalid');
        if (!this.d.canOwner()) {
          const known = new Map((await this.labPrices()).items.map((p) => [p.id, p.price]));
          if (d.lines.some((l) => l.testId && known.has(l.testId) && l.price < known.get(l.testId)! - 0.001)) throw err('owner_only');
          if (discountShare(d.lines.map((l) => ({ qty: l.qty ?? 1, price: l.price, disc: l.disc })), d.disc) > 0.1 + 1e-9) throw err('owner_only');
        }
        invoice = buildLabBill(d, issue());
        break;
      }
      case 'fee': {
        const d = op.data;
        if (!d || !['new', 'followup', 'report'].includes(d.visitType) || !nonneg(d.amount) || (!d.appointmentId && !d.walkIn?.name?.trim())) throw err('invalid');
        const std = this.visitFees[d.visitType];
        if (!this.d.canOwner() && std != null && d.amount < std - 0.001) throw err('owner_only');
        let appt: ChamberAppt | null = null;
        if (d.appointmentId) appt = (await this.findAppt(d.appointmentId)) ?? null;
        const r = buildFee(d, issue(), appt);
        invoice = r.invoice;
        effects = r.effects;
        break;
      }
      case 'purchase': {
        const d = op.data;
        if (!Array.isArray(d?.lines) || !d.lines.length || d.lines.some((l) => !l?.name?.trim() || !pos(l.qty) || !nonneg(l.cost))) throw err('invalid');
        if (!this.d.canOwner()) {
          const items = await this.itemMap(d.lines.map((l) => l.itemId).filter(Boolean) as string[]);
          if (d.lines.some((l) => l.price != null && (!l.itemId || Math.abs((items.get(l.itemId)?.price ?? -1) - l.price) > 0.001))) throw err('owner_only');
        }
        effects = d.lines.filter((l) => l.itemId).map((l) => ({ t: 'stock' as const, itemId: l.itemId!, delta: Number(l.qty) }));
        break;
      }
      case 'adjust': {
        const d = op.data;
        if (!d?.itemId || typeof d.delta !== 'number' || !Number.isFinite(d.delta) || d.delta === 0) throw err('invalid');
        if (!this.d.canOwner()) throw err('owner_only');
        effects = [{ t: 'stock', itemId: d.itemId, delta: d.delta }];
        break;
      }
      case 'walkin': {
        const d = op.data;
        if (!d?.name?.trim() || !d.chamberKey || !/^\d{4}-\d{2}-\d{2}$/.test(d.date)) throw err('invalid');
        const day = await this.chamberDay(d.date);
        const serial = Math.max(0, ...day.appointments.filter((a) => a.chamberKey === d.chamberKey).map((a) => a.serial ?? 0)) + 1;
        const appt: ChamberAppt = { id: `local:${localId}`, date: d.date, chamberKey: d.chamberKey, name: d.name.trim().slice(0, 80), serial, status: 'waiting', local: true };
        if (d.visitType) appt.visitType = d.visitType;
        effects = [{ t: 'appt_add', appt }];
        data.serial = serial;
        break;
      }
      case 'checkin': {
        if (!op.data?.appointmentId) throw err('invalid');
        effects = [{ t: 'appt_set', id: op.data.appointmentId, patch: { status: 'arrived', checkedIn: true } }];
        break;
      }
      case 'pay':
      case 'void': {
        const d = op.data as Partial<PayData & VoidData>;
        if (op.type === 'void' && !this.d.canOwner()) throw err('owner_only');
        if (op.type === 'pay' && (!pos(d?.amount) || !['cash', 'bkash', 'nagad', 'card', 'other'].includes(String(d.method)))) throw err('invalid');
        if (op.type === 'void' && !d?.reason?.trim()) throw err('invalid');
        const found = await this.findInvoice(d?.invoice);
        if (!found) throw new OfflineError(T.invoiceMissing(), 'invalid');
        const { key, inv, opId: refOp } = found;
        if (inv.status === 'void') throw new OfflineError(T.alreadyVoid(), 'invalid');
        if (op.type === 'pay') {
          if ((op.data as any).amount > inv.due + 0.001) throw new OfflineError(T.tooMuch(), 'invalid');
          invoice = applyPay(inv, (op.data as any).amount, (op.data as any).method, (op.data as any).ref, at);
        } else {
          invoice = applyVoid(inv, (op.data as any).reason.trim());
          // ফার্মেসির বিক্রি বাতিল: স্টক ফেরত (এই কম্পিউটারে বানানো হলে লাইনের জিনিস জানা আছে)
          if (inv.kind === 'pharmacy' && refOp) effects = await this.reverseStock(refOp);
        }
        invoiceKey = key;
        no = '';
        data.invoice = { ...(inv.id ? { id: inv.id } : {}), ...(inv.localId ? { localId: inv.localId } : {}), ...(refOp ? { opId: refOp } : {}), no: inv.no };
        break;
      }
    }

    if (invoice && (op.type === 'sale' || op.type === 'lab_bill' || op.type === 'fee')) {
      invoiceKey = localId;
      Object.assign(data, {
        no,
        verifyCode: invoice.verifyCode,
        issuedAt: at,
        totals: { subtotal: invoice.subtotal, disc: invoice.disc, total: invoice.total, paid: invoice.paid, due: invoice.due },
        payments: invoice.payments,
        lines: invoice.lines.map((l, i) => ({ ...(op.data as any).lines?.[i], ...l })),
      });
      // খাতায় রসিদের পুরো রূপও (মাঝপথে বন্ধ হলে repair) — সার্ভারে যায় না
      data._invoice = invoice;
    }

    const seq = this.nextSeq;
    await db.execute(
      `INSERT INTO ops (opId, seq, type, at, data, effects, localId, no, invoiceKey, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')`,
      [opId, seq, op.type, at, await this.seal(data), await this.seal(effects), localId, no, invoiceKey],
    );
    this.nextSeq = seq + 1;
    this.pendingOps.push({ opId, seq, type: op.type, at, data, effects, localId, no, invoiceKey, tries: 0 });
    if (invoice && invoiceKey) await this.putInvoice(invoiceKey, invoice);
    this.emit({ pending: this.pendingOps.length });
    this.schedule(300);
    return { localId, no: no || invoice?.no || '', invoice };
  }

  private async checkPrices(lines: { itemId?: string; price: number; qty: number; disc?: number }[], billDisc?: number) {
    if (this.d.canOwner()) return;
    const items = await this.itemMap(lines.map((l) => l.itemId).filter(Boolean) as string[]);
    for (const l of lines) {
      const it = l.itemId ? items.get(l.itemId) : undefined;
      if (it && Math.abs(it.price - l.price) > 0.001) throw err('owner_only');
    }
    if (discountShare(lines, billDisc) > 0.1 + 1e-9) throw err('owner_only');
  }

  private async itemMap(ids: string[]): Promise<Map<string, OfflineItem>> {
    const out = new Map<string, OfflineItem>();
    if (!ids.length) return out;
    const rows = await this.need().select<{ json: string }>(`SELECT json FROM items WHERE id IN (${ids.map(() => '?').join(', ')})`, ids);
    for (const r of rows) {
      const it = JSON.parse(r.json) as OfflineItem;
      out.set(it.id, it);
    }
    return out;
  }

  private async findAppt(id: string): Promise<ChamberAppt | undefined> {
    const rows = await this.need().select<{ json: string }>(`SELECT json FROM appts WHERE id = ?`, [id]);
    if (rows.length) return this.open_<ChamberAppt>(rows[0].json);
    for (const op of this.pendingOps) for (const e of op.effects) if (e.t === 'appt_add' && e.appt.id === id) return e.appt;
    return undefined;
  }

  private async findInvoice(ref: { id?: string; localId?: string; invoice?: Invoice } | undefined): Promise<{ key: string; inv: Invoice; opId?: string } | null> {
    if (!ref) return null;
    const db = this.need();
    let row: { key: string; json: string } | undefined;
    if (ref.localId) row = (await db.select<{ key: string; json: string }>(`SELECT key, json FROM invoices WHERE key = ?`, [ref.localId]))[0];
    if (!row && ref.id) row = (await db.select<{ key: string; json: string }>(`SELECT key, json FROM invoices WHERE id = ? OR key = ?`, [ref.id, `srv:${ref.id}`]))[0];
    let inv: Invoice | null = row ? await this.open_<Invoice>(row.json) : null;
    let key = row?.key ?? '';
    if (!inv && ref.invoice && ref.invoice.id && ref.invoice.bizId === this.scope?.bizId) {
      inv = { ...ref.invoice };
      key = `srv:${inv.id}`;
    }
    if (!inv) return null;
    const ops = await db.select<{ opId: string }>(`SELECT opId FROM ops WHERE invoiceKey = ? AND type IN ('sale', 'lab_bill', 'fee')`, [key]);
    return { key, inv, opId: ops[0]?.opId };
  }

  private async reverseStock(saleOpId: string): Promise<Effect[]> {
    const rows = await this.need().select<{ data: string }>(`SELECT data FROM ops WHERE opId = ? AND type = 'sale'`, [saleOpId]);
    if (!rows.length) return [];
    const d = await this.open_<Record<string, any>>(rows[0].data);
    return ((d.lines ?? []) as any[]).filter((l) => l.itemId).map((l) => ({ t: 'stock' as const, itemId: String(l.itemId), delta: Number(l.qty) }));
  }

  private async putInvoice(key: string, inv: Invoice) {
    await this.need().execute(
      `INSERT INTO invoices (key, id, no, issuedAt, json) VALUES (?, ?, ?, ?, ?) ON CONFLICT(key) DO UPDATE SET id = excluded.id, no = excluded.no, json = excluded.json`,
      [key, inv.id || null, inv.no, inv.issuedAt, await this.seal(inv)],
    );
  }

  private async getInvoice(key: string): Promise<Invoice | null> {
    const r = await this.need().select<{ json: string }>(`SELECT json FROM invoices WHERE key = ?`, [key]);
    return r.length ? this.open_<Invoice>(r[0].json) : null;
  }

  // ---------------------------------------------------------------- সিঙ্ক

  private schedule(ms: number) {
    if (this.stopped || !this.db) return;
    if (this.timer) this.d.clearTimer(this.timer);
    this.timer = this.d.setTimer(() => {
      this.timer = null;
      void this.syncLoop(false);
    }, ms);
  }

  private syncing: Promise<void> | null = null;

  private syncLoop(manual: boolean): Promise<void> {
    if (this.syncing) return this.syncing;
    this.syncing = this.syncLoop_(manual).finally(() => {
      this.syncing = null;
    });
    return this.syncing;
  }

  private async syncLoop_(manual: boolean): Promise<void> {
    if (!this.db || !this.dev || this.status.revoked || this.stopped) return;
    const online = this.d.online();
    this.emit({ online });
    if (!online && !manual) {
      this.schedule(this.pendingOps.length ? 15_000 : PULL_MS);
      return;
    }
    this.emit({ syncing: true });
    try {
      // পাঠানোর মতো কিছু না থাকলেও একবার (delta টানা); এগোলে পরের ১০০টা, না এগোলে (নতুন কোড দিয়ে আবার) পরের ঘুরে
      for (let round = 0; round < 20; round++) {
        const before = this.pendingOps.length;
        this.reissued = false;
        await this.syncBatch(this.pendingOps.slice(0, BATCH));
        if (!this.pendingOps.length || this.status.revoked || this.stopped || (this.pendingOps.length >= before && !this.reissued)) break;
      }
      this.fails = 0;
      this.emit({ syncing: false, online: true, lastSyncAt: this.d.now(), lastError: undefined });
      if (!this.status.revoked) this.schedule(this.pendingOps.length ? 2_000 : PULL_MS);
    } catch (e) {
      const a = e as ApiError;
      if (a?.code === 'device_revoked' || (a?.status === 403 && /revoked/.test(a?.code ?? ''))) {
        await this.wipeRevoked();
        return;
      }
      this.fails++;
      const wait = BACKOFF[Math.min(this.fails - 1, BACKOFF.length - 1)];
      const jitter = Math.floor(wait * 0.2 * Math.random());
      this.emit({ syncing: false, online: a?.status !== 0 && this.d.online(), lastError: a?.message || String(a?.code ?? e) });
      // 423 (অ্যাকাউন্ট বন্ধ) / অন্য 403: অনেক পরে আবার
      this.schedule(a?.status === 423 || a?.status === 403 ? 600_000 : wait + jitter);
    }
  }

  private async syncBatch(batch: OpRow[]) {
    const wire: SyncOpWire[] = batch.map((o) => ({ opId: o.opId, type: o.type, at: o.at, data: wireData(o.data) }));
    const res = await this.d.api.sync(this.dev!, wire, this.asOf);
    const delta = parseSnapshot(res?.delta ?? {}, this.dev!.series);
    await this.serial(() => this.applyResults(batch, res?.results ?? [], delta));
  }

  private async applyResults(batch: OpRow[], results: SyncResult[], delta: Snapshot) {
    const db = this.need();
    // ১) সার্ভারের নতুন অবস্থা
    const deltaItems = new Set((delta.items ?? []).map((i) => i.id));
    const deltaAppts = new Set((delta.appts ?? []).map((a) => a.id));
    if (delta.items?.length) await this.upsertItems(delta.items);
    for (const id of delta.removedItems) await db.execute(`DELETE FROM items WHERE id = ?`, [id]);
    if (delta.labPrices) {
      await db.execute(`DELETE FROM lab_prices`);
      await this.upsertLab(delta);
    }
    if (delta.appts?.length) await this.upsertAppts(delta.appts);
    for (const id of delta.removedAppts) await db.execute(`DELETE FROM appts WHERE id = ?`, [id]);
    await this.saveState(delta, false);

    // ২) প্রতিটা কাজের ফল
    const byId = new Map(results.map((r) => [String(r.opId), r]));
    let rejectedNow = 0;
    for (const op of batch) {
      const r = byId.get(op.opId);
      if (!r) continue; // উত্তর আসেনি — পরে আবার
      if (r.ok) {
        await this.foldEffects(op.effects, deltaItems, deltaAppts);
        await db.execute(`UPDATE ops SET state = 'done', result = ?, doneAt = ?, data = ?, effects = ? WHERE opId = ?`, [
          JSON.stringify({ ok: true, id: r.id ?? null, no: r.no ?? null, warn: r.warn ?? null }),
          this.d.now(),
          await this.seal(scrubPhone(op.data)),
          await this.seal([]),
          op.opId,
        ]);
        this.drop(op.opId);
        if (op.invoiceKey) await this.markInvoice(op, r);
      } else if (r.error === 'verify_collision' && op.invoiceKey && ['sale', 'lab_bill', 'fee'].includes(op.type)) {
        await this.reissueCode(op);
      } else {
        await db.execute(`UPDATE ops SET state = 'rejected', result = ?, doneAt = ?, effects = ? WHERE opId = ?`, [
          JSON.stringify({ ok: false, error: r.error ?? 'rejected' }),
          this.d.now(),
          await this.seal([]),
          op.opId,
        ]);
        this.drop(op.opId);
        rejectedNow++;
        if (op.invoiceKey) {
          const inv = await this.getInvoice(op.invoiceKey);
          if (inv && (op.type === 'sale' || op.type === 'lab_bill' || op.type === 'fee')) await this.putInvoice(op.invoiceKey, { ...inv, sync: 'rejected', syncError: r.error ?? 'rejected' });
        }
      }
    }
    if (rejectedNow) await this.countRejected();
    this.emit({ pending: this.pendingOps.length, rejected: this.status.rejected });
  }

  private drop(opId: string) {
    this.pendingOps = this.pendingOps.filter((o) => o.opId !== opId);
  }

  /** সার্ভার নিল: id, নম্বর (বদলালে আগেরটা deviceNo), সতর্কতা; ফোন নম্বর আর রাখা হয় না */
  private async markInvoice(op: OpRow, r: SyncResult) {
    const inv = await this.getInvoice(op.invoiceKey!);
    if (!inv) return;
    const out: Invoice = { ...inv, customer: inv.customer?.name ? { name: inv.customer.name } : {} };
    if (op.type === 'sale' || op.type === 'lab_bill' || op.type === 'fee') {
      if (r.id) out.id = String(r.id);
      if (r.no && r.no !== inv.no) {
        out.deviceNo = inv.deviceNo ?? inv.no;
        out.no = String(r.no);
      }
      out.sync = 'synced';
      if (r.warn) out.warn = r.warn;
      delete out.syncError;
    } else if (!this.pendingOps.some((p) => p.invoiceKey === op.invoiceKey)) {
      out.sync = inv.id ? 'synced' : inv.sync;
    }
    await this.putInvoice(op.invoiceKey!, out);
  }

  /** verify_collision: নতুন কোড আর নতুন opId (একই opId-তে সার্ভার আগের ফলই দেয়), রসিদে "আবার ছাপুন" */
  private reissued = false;

  private async reissueCode(op: OpRow) {
    this.reissued = true;
    const db = this.need();
    const code = this.demo ? 'DEMO' + this.d.randomCode(6) : this.d.randomCode(10);
    const newId = this.d.uuid();
    const data: Record<string, any> = { ...op.data, verifyCode: code };
    if (data._invoice) data._invoice = { ...data._invoice, verifyCode: code, verifyUrl: `https://lifolo.com/v/${code}`, reprint: true };
    await db.execute(`UPDATE ops SET opId = ?, data = ?, tries = tries + 1 WHERE opId = ?`, [newId, await this.seal(data), op.opId]);
    const inv = await this.getInvoice(op.invoiceKey!);
    if (inv) await this.putInvoice(op.invoiceKey!, { ...inv, verifyCode: code, verifyUrl: `https://lifolo.com/v/${code}`, reprint: true });
    // পরের pay/void যেগুলো পুরোনো opId দেখায়
    for (const p of this.pendingOps) {
      if (p.data?.invoice?.opId === op.opId) {
        p.data = { ...p.data, invoice: { ...p.data.invoice, opId: newId } };
        await db.execute(`UPDATE ops SET data = ? WHERE opId = ?`, [await this.seal(p.data), p.opId]);
      }
    }
    op.opId = newId;
    op.data = data;
  }

  /** সার্ভার নিল কিন্তু delta-য় সেই জিনিস আসেনি: প্রভাব স্থায়ী টেবিলে মেশানো */
  private async foldEffects(effects: Effect[], deltaItems: Set<string>, deltaAppts: Set<string>) {
    const db = this.need();
    for (const e of effects) {
      if (e.t === 'stock' && !deltaItems.has(e.itemId)) {
        await db.execute(`UPDATE items SET stock = stock + ? WHERE id = ?`, [e.delta, e.itemId]);
      } else if (e.t === 'appt_add' && !deltaAppts.has(e.appt.id)) {
        await db.execute(`INSERT INTO appts (id, date, json) VALUES (?, ?, ?) ON CONFLICT(id) DO NOTHING`, [e.appt.id, e.appt.date, await this.seal(e.appt)]);
      } else if (e.t === 'appt_set' && !deltaAppts.has(e.id)) {
        const a = await this.findAppt(e.id);
        if (a) await db.execute(`UPDATE appts SET json = ? WHERE id = ?`, [await this.seal({ ...a, ...e.patch }), e.id]);
      }
    }
  }

  private async wipeRevoked() {
    const name = this.scope?.name;
    this.status.revoked = true;
    if (this.timer) this.d.clearTimer(this.timer);
    this.timer = null;
    this.stopped = true;
    try {
      await this.db?.close?.();
    } catch {
      /* মুছে ফেলা হবেই */
    }
    this.db = null;
    this.pendingOps = [];
    this.opening = null;
    if (name) {
      await this.d.secrets.del(`dev-${name}`).catch(() => {});
      await this.d.dropDb(name).catch(() => {});
    }
    this.scope = null;
    this.dev = null;
    this.emit({ revoked: true, pending: 0, syncing: false, lastError: T.revoked() });
    this.d.onWiped?.('revoked');
  }

  // ---------------------------------------------------------------- সাহায্যকারী

  private stockAdj(): Map<string, number> {
    const m = new Map<string, number>();
    for (const op of this.pendingOps) for (const e of op.effects) if (e.t === 'stock') m.set(e.itemId, (m.get(e.itemId) ?? 0) + e.delta);
    return m;
  }

  private need(): Db {
    if (!this.db) throw this.status.revoked ? err('device_revoked') : err('not_ready');
    return this.db;
  }

  private seal(v: unknown) {
    return this.cipher!.seal(v);
  }

  private open_<T>(s: string): Promise<T> {
    return this.cipher!.open<T>(s);
  }

  private serial<T>(f: () => Promise<T>): Promise<T> {
    const p = this.lock.then(f, f);
    this.lock = p.catch(() => {});
    return p;
  }

  private emit(patch: Partial<OfflineStatus>) {
    Object.assign(this.status, patch);
    const s = { ...this.status };
    for (const cb of this.listeners) {
      try {
        cb(s);
      } catch {
        /* শ্রোতার ভুল ইঞ্জিন থামায় না */
      }
    }
  }

  private apiToOffline(e: unknown): OfflineError {
    if (e instanceof OfflineError) return e;
    const a = e as ApiError;
    if (a?.code === 'device_revoked') return err('device_revoked');
    if (a?.code === 'feature_off') return err('feature_off');
    if (a?.status === 0) return err('need_online');
    return new OfflineError(a?.message || T.invalid(), 'invalid');
  }
}

function applyApptEffects(map: Map<string, ChamberAppt>, effects: Effect[], date: string) {
  for (const e of effects) {
    if (e.t === 'appt_add' && e.appt.date === date) map.set(e.appt.id, { ...e.appt });
    else if (e.t === 'appt_set' && map.has(e.id)) map.set(e.id, { ...map.get(e.id)!, ...e.patch });
  }
}

/** সার্ভারে যায় না: _invoice (স্থানীয় কপি), invoice-এর snapshot */
function wireData(d: Record<string, any>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(d)) {
    if (k === '_invoice') continue;
    if (DEV_WIRE_FIELDS.includes(k) && v && typeof v === 'object') {
      const { invoice: _snap, ...ref } = v as Record<string, unknown>;
      void _snap;
      out[k] = ref;
    } else out[k] = v;
  }
  return out;
}

function scrubPhone(d: Record<string, any>): Record<string, any> {
  const out = { ...d };
  if (out.customer?.phone) out.customer = { ...out.customer, phone: undefined };
  if (out.walkIn?.phone) out.walkIn = { ...out.walkIn, phone: undefined };
  if (out.phone) delete out.phone;
  if (out._invoice?.customer?.phone) out._invoice = { ...out._invoice, customer: { name: out._invoice.customer.name } };
  return out;
}

const round3 = (n: number) => Math.round(n * 1000) / 1000;

/** ঢাকার (UTC+6) দিনের শুরু ধরে: আজ, ৭ দিন, ৩০ দিন, এই মাস; না দিলে সব */
export function periodStart(period: InvoiceQuery['period'], now: number): number {
  if (!period) return 0;
  const off = 6 * 3600_000;
  const d = new Date(now + off);
  const today = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) - off;
  if (period === 'today') return today;
  if (period === '7d') return today - 6 * DAY;
  if (period === '30d') return today - 29 * DAY;
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1) - off;
}
const likeEsc = (s: string) => s.replace(/[\\%_]/g, (c) => '\\' + c);
const safeJson = (s: string | null | undefined): any => {
  try {
    return s ? JSON.parse(s) : null;
  } catch {
    return null;
  }
};

/** ফাইলের নামে uid/bizId সরাসরি নয় — ছোট hash (FNV-1a ৬৪ বিট) */
export function hashName(s: string): string {
  let h = 0xcbf29ce484222325n;
  for (const b of new TextEncoder().encode(s)) {
    h ^= BigInt(b);
    h = (h * 0x100000001b3n) & 0xffffffffffffffffn;
  }
  return h.toString(16).padStart(16, '0');
}
