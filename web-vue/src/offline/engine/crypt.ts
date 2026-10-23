// অফলাইন ডেটাবেসের ব্যক্তিগত অংশ (রসিদ, কাজের খাতা, রোগীর নাম) AES-GCM-এ এনক্রিপ্ট — চাবি Windows DPAPI-তে রাখা
// (Rust: secret_get("dbkey")), তাই ডিস্ক খুলে বা অন্য Windows ব্যবহারকারী হয়ে ফাইল কপি করলে পড়া যায় না (নিরাপত্তা পর্যালোচনা I4)।
// ফরম্যাট: "g1:" + base64(iv ১২ বাইট ‖ ciphertext+tag)

export interface Cipher {
  seal(v: unknown): Promise<string>;
  open<T = unknown>(s: string): Promise<T>;
}

const b64 = (u: Uint8Array) => {
  let s = '';
  for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode(...u.subarray(i, i + 0x8000));
  return btoa(s);
};
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

export async function makeCipher(raw: Uint8Array): Promise<Cipher> {
  if (raw.length !== 32) throw new Error('db key must be 32 bytes');
  const key = await crypto.subtle.importKey('raw', raw as BufferSource, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  return {
    async seal(v) {
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(JSON.stringify(v ?? null))));
      const out = new Uint8Array(12 + ct.length);
      out.set(iv);
      out.set(ct, 12);
      return 'g1:' + b64(out);
    },
    async open<T>(s: string): Promise<T> {
      if (!s.startsWith('g1:')) throw new Error('not sealed');
      const all = unb64(s.slice(3));
      const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: all.subarray(0, 12) as BufferSource }, key, all.subarray(12) as BufferSource);
      return JSON.parse(dec.decode(pt)) as T;
    },
  };
}

export const randomKey = () => crypto.getRandomValues(new Uint8Array(32));
export const toB64 = b64;
export const fromB64 = unb64;

/** CSPRNG থেকে কোড (৩২টা চিহ্ন, ভুল পড়ার মতো 0/O/1/I বাদ) — যাচাই কোড ১০ অক্ষর (I14) */
const ALPHA = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
export function randomCode(len: number): string {
  const b = crypto.getRandomValues(new Uint8Array(len));
  let s = '';
  for (const x of b) s += ALPHA[x & 31];
  return s;
}

/** HMAC-SHA256 → ছোট হাতের hex (offline_sync-এর X-Lifolo-Device-Sig) */
export async function hmacHex(secret: string, msg: string): Promise<string> {
  const enc = new TextEncoder();
  const k = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = new Uint8Array(await crypto.subtle.sign('HMAC', k, enc.encode(msg)));
  return Array.from(sig, (x) => x.toString(16).padStart(2, '0')).join('');
}

/** X-Lifolo-Device-Sig: t=<unix s>,v1=<hex HMAC-SHA256(secret, "<t>.<deviceId>.<body>")> */
export async function deviceSig(secret: string, deviceId: string, body: string, nowMs: number): Promise<string> {
  const t = Math.floor(nowMs / 1000);
  return `t=${t},v1=${await hmacHex(secret, `${t}.${deviceId}.${body}`)}`;
}
