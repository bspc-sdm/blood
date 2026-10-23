// কল আসার খবর, ওয়েব খোলা থাকলে (ওয়েবে পুশ নেই):
// ১. SSE — GET /api/events (নিজের স্ট্রিম, Bearer টোকেন লাগে বলে EventSource নয়, fetch দিয়ে পড়া):
//    `call_incoming` {callId, video, ctx, refId, caller}, `call_state` {callId, state}, `appt_call_ready` {appointmentId},
//    ভিডিওতে যাওয়া: `call_upgrade` {callId} (ওপাশ চাইছেন), `call_upgraded` {callId, token?, url?}, `call_upgrade_declined` {callId}
// ২. SSE না চললে (প্রক্সি কেটে দেয়, পুরোনো ব্রাউজার) — call_history ১৫ সেকেন্ড পরপর, "আমাকে করা, এখনো রিং হচ্ছে" কল।
// ট্যাব আবার সামনে এলে একবার দেখে নেওয়া।
import { auth } from '@/lib/firebase';
import { testUser } from '@/lib/testmode';
import { callHistory, parseIncoming, parseState } from './api';
import { onApptReady, onIncoming, onRemoteState, onUpgradeDeclined, onUpgraded, onUpgradeRequest } from './state';

export const eventTiming = { backoffMs: [2000, 5000, 10000, 30000], pollMs: 15000, recentMs: 60_000 };

let stopFn: (() => void) | null = null;

/** SSE-র একটা ঘটনা */
export function handleEvent(name: string, data: string) {
  let o: any = null;
  try {
    o = JSON.parse(data);
  } catch {
    return;
  }
  if (name === 'call_incoming' || o?.type === 'call_incoming') {
    const inc = parseIncoming(o);
    if (inc) onIncoming(inc);
  } else if (name === 'call_state' || o?.type === 'call_state') {
    const st = parseState(o?.state);
    if (o?.callId && st) onRemoteState(String(o.callId), st);
  } else if (name === 'call_upgrade' || o?.type === 'call_upgrade') {
    if (o?.callId) onUpgradeRequest(String(o.callId));
  } else if (name === 'call_upgraded' || o?.type === 'call_upgraded') {
    if (o?.callId) onUpgraded(String(o.callId), o.token && o.url ? { callId: String(o.callId), room: String(o.room ?? ''), url: String(o.url), token: String(o.token) } : null);
  } else if (name === 'call_upgrade_declined' || o?.type === 'call_upgrade_declined') {
    if (o?.callId) onUpgradeDeclined(String(o.callId));
  } else if (name === 'appt_call_ready' || o?.type === 'appt_call_ready') {
    if (o?.appointmentId) onApptReady(String(o.appointmentId));
  }
}

/** text/event-stream পড়া: খালি লাইনে একটা ঘটনা শেষ */
export function sseParser(onEvent: (name: string, data: string) => void) {
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
      } else if (line.startsWith(':')) {
        /* ping */
      } else {
        const c = line.indexOf(':');
        const field = c < 0 ? line : line.slice(0, c);
        const value = c < 0 ? '' : line.slice(c + 1).replace(/^ /, '');
        if (field === 'event') name = value;
        else if (field === 'data') data.push(value);
      }
    }
  };
}

async function token(): Promise<string | null> {
  const u = auth.currentUser ?? testUser();
  return u ? u.getIdToken(false) : null;
}

/** লগইন থাকলে শোনা শুরু; আবার ডাকলে আগেরটা বন্ধ করে নতুন */
export function startCallEvents() {
  stopCallEvents();
  let stopped = false;
  let ctrl: AbortController | null = null;
  let fails = 0;
  let retry: ReturnType<typeof setTimeout> | null = null;
  let poll: ReturnType<typeof setInterval> | null = null;
  let sseUp = false;

  const pollOnce = async () => {
    if (stopped || document.visibilityState === 'hidden') return;
    try {
      const { items } = await callHistory();
      const now = Date.now();
      for (const x of items) {
        if (x.dir === 'in' && x.state === 'ringing' && (!x.startedAt || now - x.startedAt < eventTiming.recentMs)) {
          onIncoming({ callId: x.callId, video: x.video, ctx: x.ctx, refId: x.refId, caller: x.peer });
        }
      }
    } catch {
      /* পরে আবার */
    }
  };
  const setPolling = (on: boolean) => {
    if (on && !poll) poll = setInterval(pollOnce, eventTiming.pollMs);
    if (!on && poll) {
      clearInterval(poll);
      poll = null;
    }
  };

  const connect = async () => {
    if (stopped) return;
    ctrl = new AbortController();
    try {
      const t = await token();
      if (!t || stopped) return;
      const res = await fetch('/api/events', { headers: { Authorization: `Bearer ${t}`, Accept: 'text/event-stream' }, signal: ctrl.signal, credentials: 'omit', cache: 'no-store' });
      if (!res.ok || !res.body) throw new Error(`sse ${res.status}`);
      sseUp = true;
      fails = 0;
      setPolling(false);
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      const feed = sseParser(handleEvent);
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        feed(dec.decode(value, { stream: true }));
      }
      throw new Error('sse closed');
    } catch {
      sseUp = false;
      if (stopped) return;
      fails++;
      // দুবার না পারলে (বা একবারও না) বিকল্প: তালিকা দেখা
      if (fails >= 2) setPolling(true);
      const wait = eventTiming.backoffMs[Math.min(fails - 1, eventTiming.backoffMs.length - 1)];
      retry = setTimeout(connect, wait);
    }
  };
  const onVisible = () => {
    if (document.visibilityState === 'visible' && !sseUp) void pollOnce();
  };
  document.addEventListener('visibilitychange', onVisible);
  void connect();
  stopFn = () => {
    stopped = true;
    ctrl?.abort();
    if (retry) clearTimeout(retry);
    setPolling(false);
    document.removeEventListener('visibilitychange', onVisible);
  };
}

export function stopCallEvents() {
  stopFn?.();
  stopFn = null;
}
