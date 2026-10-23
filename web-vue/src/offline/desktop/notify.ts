// Windows নোটিফিকেশন — Lifolo চালু থাকলে (ট্রেতে লুকানো থাকলেও): ওয়েবের একই SSE (GET /api/events, Bearer)।
//  • call_incoming → "কল আসছে" + জানালা সামনে (কল ধরতে হলে জানালা লাগে)
//  • push / *_order_new / invoice_new … → নোটিফিকেশন (জানালা সামনে না থাকলে)
//  • features_changed → ফিচার সুইচ আবার পড়া
// SSE বন্ধ হলে পিছিয়ে আবার (২ সে → ৫ মিনিট)।
import { getCurrentWindow, UserAttentionType } from '@tauri-apps/api/window';
import { isPermissionGranted, requestPermission, sendNotification } from '@tauri-apps/plugin-notification';
import { auth } from '@/lib/firebase';
import { T } from '../text';

const KEY = 'lf.desk.notify';
export const notifyOn = () => {
  try {
    return localStorage.getItem(KEY) !== '0';
  } catch {
    return true;
  }
};
export function setNotifyOn(v: boolean) {
  try {
    localStorage.setItem(KEY, v ? '1' : '0');
  } catch {
    /* এই সেশনেই */
  }
}

let permitted: boolean | null = null;
export async function notify(title: string, body: string) {
  if (!notifyOn()) return;
  try {
    permitted ??= (await isPermissionGranted()) || (await requestPermission()) === 'granted';
    if (permitted) sendNotification({ title, body });
  } catch {
    /* নোটিফিকেশন ছাড়াই */
  }
}

async function windowAway(): Promise<boolean> {
  try {
    const w = getCurrentWindow();
    return !(await w.isVisible()) || (await w.isMinimized()) || !(await w.isFocused());
  } catch {
    return true;
  }
}

async function bringFront() {
  try {
    const w = getCurrentWindow();
    await w.unminimize();
    await w.show();
    await w.setFocus();
    await w.requestUserAttention(UserAttentionType.Critical);
  } catch {
    /* জানালা ছাড়া */
  }
}

const ORDER_EVENTS = /(^|_)(order|orders)_new$|^invoice_new$|^ambreq_new$|^appt_new$/;

export async function handleDeskEvent(name: string, data: string, onFeatures: () => void) {
  let o: any = {};
  try {
    o = JSON.parse(data);
  } catch {
    o = {};
  }
  const type = String(o?.type ?? name);
  if (type === 'call_incoming') {
    const who = String(o?.caller?.name ?? o?.callerName ?? '').trim();
    await notify(o?.video ? T.videoIn() : T.callIn(), who ? T.callFrom(who) : 'Lifolo');
    await bringFront();
  } else if (type === 'features_changed') {
    onFeatures();
  } else if (type === 'push' || ORDER_EVENTS.test(type)) {
    if (!(await windowAway())) return;
    const title = String(o?.title ?? '').trim() || T.newOrder();
    const body = String(o?.body ?? o?.text ?? '').trim();
    await notify(title, body);
  }
}

/** text/event-stream পড়া */
export function sse(onEvent: (name: string, data: string) => void) {
  let buf = '';
  let name = '';
  let data: string[] = [];
  return (chunk: string) => {
    buf += chunk;
    let i: number;
    while ((i = buf.search(/\r?\n/)) >= 0) {
      const line = buf.slice(0, i);
      buf = buf.slice(buf[i] === '\r' ? i + 2 : i + 1);
      if (line === '') {
        if (data.length) onEvent(name || 'message', data.join('\n'));
        name = '';
        data = [];
      } else if (!line.startsWith(':')) {
        const c = line.indexOf(':');
        const f = c < 0 ? line : line.slice(0, c);
        const v = c < 0 ? '' : line.slice(c + 1).replace(/^ /, '');
        if (f === 'event') name = v;
        else if (f === 'data') data.push(v);
      }
    }
  };
}

const BACKOFF = [2_000, 5_000, 15_000, 30_000, 60_000, 300_000];

/** লগইন থাকা পর্যন্ত শোনা; ফেরত = বন্ধ করার ফাংশন */
export function listenEvents(onFeatures: () => void): () => void {
  let stopped = false;
  let ctrl: AbortController | null = null;
  let fails = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const run = async () => {
    if (stopped) return;
    const u = auth.currentUser;
    if (!u) return;
    try {
      const token = await u.getIdToken(false);
      ctrl = new AbortController();
      const res = await fetch('/api/events', { headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }, signal: ctrl.signal, cache: 'no-store' });
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
      fails = 0;
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      const feed = sse((n, d) => void handleDeskEvent(n, d, onFeatures));
      for (;;) {
        const { done, value } = await reader.read();
        if (done || stopped) break;
        feed(dec.decode(value, { stream: true }));
      }
    } catch {
      fails++;
    }
    if (!stopped) timer = setTimeout(run, BACKOFF[Math.min(fails, BACKOFF.length - 1)]);
  };
  void run();
  return () => {
    stopped = true;
    ctrl?.abort();
    if (timer) clearTimeout(timer);
  };
}
