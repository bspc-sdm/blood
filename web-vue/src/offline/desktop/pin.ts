// পিন লক (CONTRACT: ১০ মিনিট কিছু না করলে লক) আর দুই স্তর (নিরাপত্তা পর্যালোচনা I7): মালিকের পিন আর কাউন্টারের পিন।
//  • পিন সংরক্ষণ: PBKDF2-SHA256 (২,১০,০০০ বার), আলাদা salt — DPAPI-তে (SQLite-এ নয়), তাই ফাইল কপি করে অনুমান করা যায় না।
//  • ৫ বার ভুলের পর অপেক্ষা বাড়ে (৩০ সে, ১ মি, ২ মি …); ১০ বার ভুল হলে এই কম্পিউটারের অফলাইন তথ্য মুছে লগআউট (I4)।
//  • requireOwner(): কাউন্টারের পিনে খোলা থাকলে মালিকের পিন চায় — একটা কাজের জন্য ৬০ সেকেন্ড।
import { reactive } from 'vue';
import type { DeskRole } from '../types';

export const IDLE_MS = 10 * 60_000;
export const WIPE_AFTER = 10;
const ITER = 210_000;
const ELEVATE_MS = 60_000;

interface PinHash {
  salt: string;
  hash: string;
  iter: number;
}
interface PinRecord {
  owner?: PinHash;
  counter?: PinHash;
  fails: number;
  until: number;
}

export interface PinStore {
  get(name: string): Promise<string | null>;
  set(name: string, v: string): Promise<void>;
}

const hex = (b: ArrayBuffer | Uint8Array) => Array.from(new Uint8Array(b), (x) => x.toString(16).padStart(2, '0')).join('');
const unhex = (s: string) => Uint8Array.from(s.match(/../g) ?? [], (h) => parseInt(h, 16));

async function derive(pin: string, salt: Uint8Array, iter: number): Promise<string> {
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits']);
  return hex(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations: iter }, k, 256));
}

async function makeHash(pin: string, iter = ITER): Promise<PinHash> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return { salt: hex(salt), hash: await derive(pin, salt, iter), iter };
}

async function matches(pin: string, h: PinHash | undefined): Promise<boolean> {
  if (!h) return false;
  const got = await derive(pin, unhex(h.salt), h.iter);
  let diff = got.length ^ h.hash.length;
  for (let i = 0; i < Math.min(got.length, h.hash.length); i++) diff |= got.charCodeAt(i) ^ h.hash.charCodeAt(i);
  return diff === 0;
}

export const validPin = (p: string) => /^\d{4,6}$/.test(p);

export type UnlockResult = { ok: true; role: DeskRole } | { ok: false; waitMs: number; left: number; wiped: boolean };

export class PinLock {
  readonly state = reactive({
    /** পিন আছে, আর এখন লক */
    locked: false,
    role: null as DeskRole | null,
    hasOwner: false,
    hasCounter: false,
    /** মালিকের পিন চাওয়া হচ্ছে (requireOwner) */
    asking: false,
    fails: 0,
    until: 0,
  });
  private rec: PinRecord = { fails: 0, until: 0 };
  private name = '';
  private lastActive = 0;
  private elevatedUntil = 0;
  private ask: ((ok: boolean) => void) | null = null;

  constructor(
    private store: PinStore,
    private onWipe: () => Promise<void>,
    private now: () => number = Date.now,
    private iter = ITER,
  ) {}

  async load(key: string) {
    this.name = `pins-${key}`;
    const raw = await this.store.get(this.name).catch(() => null);
    try {
      const r = raw ? (JSON.parse(raw) as Partial<PinRecord>) : {};
      this.rec = { ...r, fails: Number(r.fails) || 0, until: Number(r.until) || 0 };
    } catch {
      this.rec = { fails: 0, until: 0 };
    }
    this.sync();
    // অ্যাপ চালু হলে (পিন থাকলে) লক — দোকানের কম্পিউটার খুললেই কেউ বিক্রি করতে পারবে না
    this.state.locked = !!this.rec.owner;
    this.state.role = this.rec.owner ? null : 'owner';
    this.lastActive = this.now();
  }

  unload() {
    this.name = '';
    this.rec = { fails: 0, until: 0 };
    this.sync();
    this.state.locked = false;
    this.state.role = null;
    this.cancelAsk();
  }

  private sync() {
    this.state.hasOwner = !!this.rec.owner;
    this.state.hasCounter = !!this.rec.counter;
    this.state.fails = this.rec.fails;
    this.state.until = this.rec.until;
  }

  private async save() {
    this.sync();
    if (this.name) await this.store.set(this.name, JSON.stringify(this.rec));
  }

  /** মালিক নিজে, বা পিন নেই, বা এইমাত্র মালিকের পিন দেওয়া */
  canOwner(): boolean {
    return !this.rec.owner || this.state.role === 'owner' || this.now() < this.elevatedUntil;
  }

  role(): DeskRole {
    return this.state.role ?? 'owner';
  }

  activity() {
    this.lastActive = this.now();
  }

  /** প্রতি ১৫ সেকেন্ডে: ১০ মিনিট কিছু না হলে লক */
  idleCheck() {
    if (this.rec.owner && !this.state.locked && this.now() - this.lastActive >= IDLE_MS) this.lock();
  }

  lock() {
    if (!this.rec.owner) return;
    this.state.locked = true;
    this.state.role = null;
    this.elevatedUntil = 0;
  }

  async setPin(kind: DeskRole, pin: string): Promise<void> {
    if (!validPin(pin)) throw new Error('bad pin');
    if (kind === 'counter' && !this.rec.owner) throw new Error('owner first');
    if (kind === 'counter' && (await matches(pin, this.rec.owner))) throw new Error('same as owner');
    if (kind === 'owner' && (await matches(pin, this.rec.counter))) throw new Error('same as counter');
    this.rec[kind] = await makeHash(pin, this.iter);
    if (kind === 'owner' && !this.state.role) this.state.role = 'owner';
    await this.save();
  }

  async removeCounter(): Promise<void> {
    delete this.rec.counter;
    await this.save();
  }

  /** লক খোলা: মালিক না কাউন্টার — পিন দেখে */
  async unlock(pin: string): Promise<UnlockResult> {
    const r = await this.check(pin, true);
    if (r.ok) {
      this.state.locked = false;
      this.state.role = r.role;
      this.lastActive = this.now();
    }
    return r;
  }

  private async check(pin: string, counterOk: boolean): Promise<UnlockResult> {
    const n = this.now();
    if (n < this.rec.until) return { ok: false, waitMs: this.rec.until - n, left: WIPE_AFTER - this.rec.fails, wiped: false };
    let role: DeskRole | null = null;
    if (await matches(pin, this.rec.owner)) role = 'owner';
    else if (counterOk && (await matches(pin, this.rec.counter))) role = 'counter';
    if (role) {
      this.rec.fails = 0;
      this.rec.until = 0;
      await this.save();
      return { ok: true, role };
    }
    this.rec.fails++;
    if (this.rec.fails >= WIPE_AFTER) {
      await this.onWipe();
      return { ok: false, waitMs: 0, left: 0, wiped: true };
    }
    this.rec.until = this.rec.fails >= 5 ? n + Math.min(15 * 60_000, 30_000 * 2 ** (this.rec.fails - 5)) : 0;
    await this.save();
    return { ok: false, waitMs: Math.max(0, this.rec.until - n), left: WIPE_AFTER - this.rec.fails, wiped: false };
  }

  /** কাউন্টারে খোলা থাকলে মালিকের পিন চাওয়া (পর্দায় OwnerPrompt) */
  requireOwner(): Promise<boolean> {
    if (this.canOwner()) return Promise.resolve(true);
    this.cancelAsk();
    this.state.asking = true;
    return new Promise((res) => {
      this.ask = res;
    });
  }

  async answerOwner(pin: string): Promise<UnlockResult> {
    const r = await this.check(pin, false);
    if (r.ok) {
      this.elevatedUntil = this.now() + ELEVATE_MS;
      this.finishAsk(true);
    } else if (r.wiped) this.finishAsk(false);
    return r;
  }

  cancelAsk() {
    this.finishAsk(false);
  }

  private finishAsk(ok: boolean) {
    this.state.asking = false;
    const f = this.ask;
    this.ask = null;
    f?.(ok);
  }
}
