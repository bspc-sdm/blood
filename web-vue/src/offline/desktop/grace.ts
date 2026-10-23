// ইন্টারনেট ছাড়া কত দিন (CONTRACT: ৭ দিন) আর কম্পিউটারের ঘড়ি (নিরাপত্তা পর্যালোচনা I12)।
//  • লগইন করা ডাক সার্ভারে পৌঁছালে (401 ছাড়া) = "অনলাইনে যাচাই": তখনকার সময় আর সার্ভারের Date রাখা হয়।
//  • কখনো দেখা সবচেয়ে বড় সময় (maxSeen) — ঘড়ি তার চেয়ে ১০ মিনিটের বেশি পিছালে লক ("সময় ঠিক করুন")।
//  • ৭ দিন গোনা হয় max(এখন, maxSeen) থেকে — ঘড়ি পিছিয়ে দিন বাড়ানো যায় না। ৬ দিনে সতর্কতা।
// DPAPI-তে রাখা (secrets), ব্যবহারকারী অনুযায়ী।
import { reactive } from 'vue';

export const DAY = 86_400_000;
export const GRACE_MS = 7 * DAY;
export const WARN_MS = 6 * DAY;
const SKEW_MS = 10 * 60_000;

export interface GraceData {
  /** শেষ অনলাইন যাচাই — এই কম্পিউটারের সময়ে */
  onlineAt: number;
  /** তখন সার্ভারের সময় */
  serverAt: number;
  maxSeen: number;
}

export type Gate = 'ok' | 'login_expired' | 'clock';

export interface GraceStore {
  get(name: string): Promise<string | null>;
  set(name: string, v: string): Promise<void>;
}

export class Grace {
  readonly state = reactive({ gate: 'ok' as Gate, offlineDays: 0, warn: false });
  private data: GraceData | null = null;
  private name = '';
  private savedAt = 0;

  constructor(private store: GraceStore, private now: () => number = Date.now) {}

  async load(key: string) {
    this.name = `grace-${key}`;
    const raw = await this.store.get(this.name).catch(() => null);
    try {
      this.data = raw ? (JSON.parse(raw) as GraceData) : null;
    } catch {
      this.data = null;
    }
    this.tick();
  }

  unload() {
    this.data = null;
    this.name = '';
    this.state.gate = 'ok';
    this.state.offlineDays = 0;
    this.state.warn = false;
  }

  /** লগইন করা ডাক সার্ভারে পৌঁছাল */
  online(serverDate: number | null) {
    if (!this.name) return;
    const n = this.now();
    // অনলাইনে নতুন করে শুরু: ঘড়ি সামনে চলে গিয়েছিল আর ফিরেছে — সার্ভার সাক্ষী, তাই maxSeen = এখন
    this.data = { onlineAt: n, serverAt: serverDate ?? n, maxSeen: n };
    this.tick();
    if (n - this.savedAt > 60_000) void this.save();
  }

  /** প্রতি মিনিটে আর প্রতিটা কাজের আগে */
  tick(): Gate {
    const n = this.now();
    const d = this.data;
    if (!d) {
      // কখনো অনলাইনে যাচাই হয়নি (নতুন বসানো, লগইনের পরে প্রথম ডাকের আগে) — কাজ শুরু হতে দেওয়া; ইঞ্জিন প্রথমবার অনলাইন চায়
      this.state.gate = 'ok';
      return 'ok';
    }
    if (n < d.maxSeen - SKEW_MS) {
      this.state.gate = 'clock';
      return 'clock';
    }
    if (n > d.maxSeen) {
      d.maxSeen = n;
      if (n - this.savedAt > 5 * 60_000) void this.save();
    }
    const age = Math.max(n, d.maxSeen) - d.onlineAt;
    this.state.offlineDays = Math.floor(age / DAY);
    this.state.warn = age >= WARN_MS && age < GRACE_MS;
    this.state.gate = age >= GRACE_MS ? 'login_expired' : 'ok';
    return this.state.gate;
  }

  async save() {
    if (!this.name || !this.data) return;
    this.savedAt = this.now();
    await this.store.set(this.name, JSON.stringify(this.data)).catch(() => {});
  }
}
