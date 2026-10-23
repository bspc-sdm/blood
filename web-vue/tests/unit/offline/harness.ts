// অফলাইন ইঞ্জিনের পরীক্ষার সরঞ্জাম: আসল SQLite (node:sqlite, মেমরিতে) আর একটা ছোট নকল সার্ভার
import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { makeCipher, randomCode } from '@/offline/engine/crypt';
import type { Db } from '@/offline/engine/db';
import { OfflineEngine, type DeviceCred, type EngineDeps, type SyncOpWire, type SyncResult } from '@/offline/engine/engine';
import type { DeskRole, Invoice } from '@/offline/types';

export function sqliteDb(): Db & { raw: DatabaseSync } {
  const raw = new DatabaseSync(':memory:');
  const fix = (p: unknown[] = []) => p.map((v) => (v === undefined ? null : typeof v === 'boolean' ? Number(v) : v)) as any[];
  return {
    raw,
    async execute(sql, params) {
      raw.prepare(sql).run(...fix(params));
    },
    async select<T>(sql: string, params?: unknown[]) {
      return raw.prepare(sql).all(...fix(params)) as T[];
    },
    async close() {},
  };
}

export class ApiErr extends Error {
  constructor(public status: number, public code?: string) {
    super(code ?? `status ${status}`);
  }
}

/** নকল সার্ভার: স্টক রাখে, opId ধরে একই ফল ফেরত দেয়, ইচ্ছেমতো ভুল */
export class FakeServer {
  items = new Map<string, { id: string; name: string; price: number; stock: number; code?: string }>();
  results = new Map<string, SyncResult>();
  applied: SyncOpWire[] = [];
  devices = 0;
  revoked = false;
  featureOff = false;
  down = false;
  /** উত্তর হারানো: সার্ভার কাজ করে, কিন্তু ফোনে উত্তর পৌঁছায় না */
  loseNextResponse = false;
  rejectTypes = new Set<string>();
  collideOnce = new Set<string>();
  renumber: Record<string, string> = {};
  sendDelta = true;
  syncCalls = 0;
  lastSig: DeviceCred | null = null;
  bootstrapExtra: Record<string, unknown> = {};

  constructor() {
    this.items.set('i1', { id: 'i1', name: 'Napa 500', price: 2, stock: 100, code: '8901' });
    this.items.set('i2', { id: 'i2', name: 'Seclo 20', price: 7, stock: 10 });
  }

  api(): EngineDeps['api'] {
    return {
      register: async () => {
        if (this.down) throw new ApiErr(0);
        if (this.featureOff) throw new ApiErr(404, 'feature_off');
        this.devices++;
        return { deviceId: `dev${this.devices}`, series: `D${this.devices}`, secret: 's3cr3t' };
      },
      bootstrap: async () => {
        if (this.down) throw new ApiErr(0);
        if (this.revoked) throw new ApiErr(403, 'device_revoked');
        return { biz: { name: 'Shefa Pharmacy', phone: '01700000000', address: 'Mymensingh' }, asOf: 1000, items: [...this.items.values()], ...this.bootstrapExtra };
      },
      sync: async (dev, ops) => {
        this.syncCalls++;
        this.lastSig = dev;
        if (this.down) throw new ApiErr(0);
        if (this.revoked) throw new ApiErr(403, 'device_revoked');
        const results: SyncResult[] = [];
        const touched = new Set<string>();
        for (const op of ops) {
          const prev = this.results.get(op.opId);
          if (prev) {
            results.push(prev);
            continue;
          }
          let r: SyncResult;
          const vc = (op.data as any).verifyCode as string | undefined;
          if (this.rejectTypes.has(op.type)) r = { opId: op.opId, ok: false, error: 'too_old' };
          else if (vc && this.collideOnce.has(vc)) {
            this.collideOnce.delete(vc);
            r = { opId: op.opId, ok: false, error: 'verify_collision' };
          } else {
            this.applied.push(op);
            const d = op.data as any;
            if (op.type === 'sale') for (const l of d.lines) if (l.itemId) this.bump(l.itemId, -l.qty, touched);
            if (op.type === 'adjust') this.bump(d.itemId, d.delta, touched);
            const no = this.renumber[d.no] ?? d.no;
            r = { opId: op.opId, ok: true, id: d.no ? `inv-${no}` : undefined, no: d.no ? no : undefined, ...(this.renumber[d.no] ? { warn: 'renumbered' } : {}) };
          }
          this.results.set(op.opId, r);
          results.push(r);
        }
        if (this.loseNextResponse) {
          this.loseNextResponse = false;
          throw new ApiErr(0);
        }
        return { results, delta: this.sendDelta ? { items: [...touched].map((id) => this.items.get(id)) } : {} };
      },
    };
  }

  private bump(id: string, d: number, touched: Set<string>) {
    const it = this.items.get(id);
    if (it) {
      it.stock += d;
      touched.add(id);
    }
  }
}

export interface Harness {
  engine: OfflineEngine;
  server: FakeServer;
  db: ReturnType<typeof sqliteDb>;
  secrets: Map<string, string>;
  dropped: string[];
  state: { now: number; online: boolean; gate: ReturnType<EngineDeps['gate']>; role: DeskRole | null; elevated: boolean };
  printed: Invoice[];
  registered: boolean[];
  reopen(): Promise<Harness>;
}

export async function harness(opts: { server?: FakeServer; db?: ReturnType<typeof sqliteDb>; secrets?: Map<string, string>; key?: Uint8Array } = {}): Promise<Harness> {
  const server = opts.server ?? new FakeServer();
  const db = opts.db ?? sqliteDb();
  const secrets = opts.secrets ?? new Map<string, string>();
  const key = opts.key ?? crypto.getRandomValues(new Uint8Array(32));
  const dropped: string[] = [];
  const printed: Invoice[] = [];
  const registered: boolean[] = [];
  const state: Harness['state'] = { now: Date.UTC(2026, 9, 23, 6, 0, 0), online: true, gate: 'ok', role: 'owner', elevated: false };
  const deps: EngineDeps = {
    openDb: async () => db,
    dropDb: async (n) => {
      dropped.push(n);
    },
    cipher: () => makeCipher(key),
    secrets: {
      get: async (n) => secrets.get(n) ?? null,
      set: async (n, v) => {
        secrets.set(n, v);
      },
      del: async (n) => {
        secrets.delete(n);
      },
    },
    api: server.api(),
    uid: () => 'u1',
    deviceName: async () => 'COUNTER-PC',
    now: () => state.now,
    uuid: () => randomUUID(),
    randomCode,
    online: () => state.online,
    gate: () => state.gate,
    role: () => state.role,
    canOwner: () => state.role !== 'counter' || state.elevated,
    requireOwner: async () => {
      state.elevated = true;
      return true;
    },
    print: async (inv) => {
      printed.push(inv);
    },
    bizInfo: () => ({ name: 'From session' }),
    onRegistered: (ok) => registered.push(ok),
    setTimer: () => 0,
    clearTimer: () => {},
  };
  const engine = new OfflineEngine(deps);
  const h: Harness = {
    engine,
    server,
    db,
    secrets,
    dropped,
    state,
    printed,
    registered,
    reopen: async () => {
      await engine.close();
      return harness({ server, db, secrets, key });
    },
  };
  return h;
}
