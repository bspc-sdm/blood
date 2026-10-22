// একটা কলের অবস্থা (পুরো ওয়েবে একটাই): কল করা → রিং → কথা → শেষ; কল আসা → ধরা/কাটা; অনলাইন অ্যাপয়েন্টমেন্টের অপেক্ষার ঘর।
// সার্ভারের খবর দুইভাবে আসে: SSE (events.ts) আর call_status বারবার দেখা (SSE না চললেও কাজ করে)।
import { reactive } from 'vue';
import { errorText, ServerError } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { TEST_MODE } from '@/lib/testmode';
import {
  apptJoin, callAccept, callCancel, callDecline, callEnd, callStart, callStatus, refusalOf,
  type CallCtx, type CallPeer, type CallState, type Incoming, type RoomGrant,
} from './api';
import { MediaError, createMedia, failOf, mediaSupported, type CallMedia, type MediaFail } from './media';
import { startRing, stopRing } from './ring';
import { T, refusalText } from './text';

export type Phase = 'idle' | 'outgoing' | 'incoming' | 'joining' | 'active' | 'ended';
export type EndReason = CallState | 'failed' | 'left' | null;
export interface ApptCall { id: string; role: 'patient' | 'provider' }

export interface CallUi {
  phase: Phase;
  callId: string | null;
  ctx: CallCtx | null;
  refId: string;
  peer: CallPeer | null;
  /** ভিডিও কল কি না (ক্যামেরা বন্ধ করলেও ভিডিও কলই থাকে) */
  video: boolean;
  appt: ApptCall | null;
  /** সংযোগ: connecting / connected / reconnecting */
  link: 'connecting' | 'connected' | 'reconnecting' | null;
  peerIn: boolean;
  /** কথা শুরুর সময় (দুজনেই যুক্ত হওয়ার পর) */
  talkingSince: number | null;
  mic: boolean;
  cam: boolean;
  remoteVideo: boolean;
  audioOnly: boolean;
  poor: boolean;
  audioBlocked: boolean;
  /** ব্রাউজারের অনুমতি চাওয়া চলছে */
  asking: boolean;
  /** ক্যামেরা/অন্য ছোট খবর (কল চলতে থাকে) */
  note: string | null;
  /** কল হলো না/থামল — কেন (দেখানো হয়, ব্যবহারকারী বন্ধ করেন) */
  error: string | null;
  /** অ্যাপে কল বন্ধ/ব্যস্ত হলে ফোনের নম্বর (প্রসঙ্গে থাকলে) */
  phone: string | null;
  endReason: EndReason;
  durationSec: number;
  /** কল আসছে: শব্দ বাজছে কি না (আগে পাতায় চাপ না পড়লে বাজে না) */
  ringAudible: boolean;
  /** ডাক্তার অনলাইন অ্যাপয়েন্টমেন্টের কলে আছেন (appt_call_ready) */
  apptReady: { appointmentId: string } | null;
}

const fresh = (): Omit<CallUi, 'apptReady'> => ({
  phase: 'idle', callId: null, ctx: null, refId: '', peer: null, video: false, appt: null, link: null, peerIn: false, talkingSince: null,
  mic: true, cam: false, remoteVideo: false, audioOnly: false, poor: false, audioBlocked: false, asking: false, note: null, error: null,
  phone: null, endReason: null, durationSec: 0, ringAudible: false,
});

export const callUi = reactive<CallUi>({ ...fresh(), apptReady: null });

let media: CallMedia | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let ringTimeout: ReturnType<typeof setTimeout> | null = null;
let endTimer: ReturnType<typeof setTimeout> | null = null;
/** কোন কল আগে দেখা হয়েছে (SSE আবার যুক্ত হলে একই কল দুবার যেন না বাজে) */
const seen = new Set<string>();
/** কোন কাজ চলছে — পুরোনো কাজের উত্তর নতুন কলে না পড়ে */
let gen = 0;
const titleBase = { v: '' };

/** পরীক্ষায় সময় ছোট করা যায় */
export const timing = { pollMs: 3000, activePollMs: 5000, ringMs: 45_000, outgoingMs: 50_000, endShowMs: 2500 };

function clearTimers() {
  if (pollTimer) clearInterval(pollTimer);
  if (ringTimeout) clearTimeout(ringTimeout);
  if (endTimer) clearTimeout(endTimer);
  pollTimer = ringTimeout = endTimer = null;
}
function setTitle(on: boolean) {
  if (on) {
    if (!titleBase.v) titleBase.v = document.title;
    document.title = `${callUi.video ? T.incomingVideo() : T.incoming()} — ${callUi.peer?.name ?? ''}`;
  } else if (titleBase.v) {
    document.title = titleBase.v;
    titleBase.v = '';
  }
}
function reset() {
  clearTimers();
  stopRing();
  setTitle(false);
  media?.disconnect();
  media = null;
  gen++;
  Object.assign(callUi, fresh());
}
export const busy = () => callUi.phase !== 'idle' && callUi.phase !== 'ended';

/** কল শেষ: কারণ দেখিয়ে কিছুক্ষণ পর বন্ধ (ভুল থাকলে ব্যবহারকারী বন্ধ করেন) */
function finish(reason: EndReason, opts: { error?: string | null; phone?: string | null; keep?: boolean } = {}) {
  const dur = callUi.talkingSince ? Math.round((Date.now() - callUi.talkingSince) / 1000) : 0;
  clearTimers();
  stopRing();
  setTitle(false);
  media?.disconnect();
  media = null;
  gen++;
  const wasIncoming = callUi.phase === 'incoming';
  Object.assign(callUi, {
    phase: 'ended', endReason: reason, durationSec: dur, link: null, peerIn: false, talkingSince: null, asking: false,
    error: opts.error ?? null, phone: opts.phone ?? null, ringAudible: false,
  });
  // কল আসছিল আর থেমে গেল (ওপাশ রেখে দিলেন/অন্য ট্যাবে ধরা হলো): কিছু দেখানোর নেই
  if (wasIncoming && !opts.error) return reset();
  if (!opts.error && !opts.keep) endTimer = setTimeout(reset, timing.endShowMs);
}
export function dismiss() {
  if (callUi.phase === 'ended') reset();
}

function mediaText(k: MediaFail): string {
  if (k === 'denied') return T.permDenied();
  if (k === 'nodevice') return T.noMic();
  if (k === 'unsupported') return T.noSupport();
  return T.failed();
}

/** অনুমতি আগে নিয়ে নেওয়া (ব্রাউজারের প্রশ্ন), যাতে না দিলে কল শুরুই না হয়। ফল: ক্যামেরা পাওয়া গেল কি না */
async function preflight(video: boolean): Promise<boolean> {
  if (TEST_MODE && (window as any).__lfTest?.fakeMedia) {
    const f = (window as any).__lfTest.fakeMedia;
    if (f.denyMic) throw new MediaError('denied');
    return video && !f.denyCam;
  }
  if (!mediaSupported()) throw new MediaError('unsupported');
  const stop = (s: MediaStream) => s.getTracks().forEach((t) => t.stop());
  if (video) {
    try {
      stop(await navigator.mediaDevices.getUserMedia({ audio: true, video: true }));
      return true;
    } catch (e) {
      // ক্যামেরা না পেলে/না দিলে শুধু মাইক দেখা — পেলে কল শুধু অডিওতে চলে
      if (failOf(e) === 'unsupported') throw new MediaError('unsupported');
    }
  }
  try {
    stop(await navigator.mediaDevices.getUserMedia({ audio: true }));
  } catch (e) {
    throw new MediaError(failOf(e));
  }
  return false;
}

/** LiveKit-এ যুক্ত হওয়া, মাইক আর (চাইলে) ক্যামেরা চালু */
async function enter(grant: RoomGrant, camOk: boolean, my: number) {
  const m = await createMedia({
    status: (s) => {
      if (my !== gen) return;
      if (s === 'disconnected') {
        if (busy()) void endOnServer().finally(() => my === gen && finish('failed', { error: T.failed() }));
        return;
      }
      callUi.link = s;
    },
    peer: (present) => {
      if (my !== gen) return;
      const was = callUi.peerIn;
      callUi.peerIn = present;
      if (present) {
        if (callUi.phase === 'outgoing') becomeActive();
        if (callUi.phase === 'active' && !callUi.talkingSince) callUi.talkingSince = Date.now();
      } else if (was && callUi.phase === 'active') {
        // অনলাইন অ্যাপয়েন্টমেন্ট: অপেক্ষার ঘরে ফেরা (আবার যুক্ত হতে পারেন); সাধারণ কল: শেষ
        if (callUi.appt) callUi.talkingSince = null;
        else void endOnServer().finally(() => my === gen && finish('left'));
      }
    },
    remoteVideo: (on) => my === gen && (callUi.remoteVideo = on),
    poor: (p) => my === gen && (callUi.poor = p),
    audioBlocked: (b) => my === gen && (callUi.audioBlocked = b),
  });
  if (my !== gen) return m.disconnect();
  media = m;
  await m.connect(grant.url, grant.token);
  if (my !== gen) return;
  await m.setMic(true);
  callUi.mic = true;
  if (callUi.video && camOk) {
    try {
      await m.setCam(true);
      callUi.cam = true;
    } catch {
      callUi.cam = false;
      callUi.note = T.camFailed();
    }
  } else if (callUi.video) {
    callUi.note = T.camFailed();
  }
}

function becomeActive() {
  stopRing();
  callUi.phase = 'active';
  callUi.asking = false;
  if (callUi.peerIn && !callUi.talkingSince) callUi.talkingSince = Date.now();
  watchActive();
}

/** কথা চলার সময়ও সার্ভার দেখা — ওপাশ রেখে দিলে (SSE না এলেও) শেষ */
function watchActive() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
  const id = callUi.callId;
  if (!id || callUi.appt) return;
  const my = gen;
  pollTimer = setInterval(async () => {
    try {
      const st = await callStatus(id);
      if (my === gen && st.state && st.state !== 'accepted' && st.state !== 'ringing') finish(st.state);
    } catch {
      /* পরে আবার */
    }
  }, timing.activePollMs);
}

async function endOnServer() {
  const id = callUi.callId;
  if (!id) return;
  try {
    if (callUi.appt) return;
    if (callUi.phase === 'outgoing') await callCancel(id);
    else await callEnd(id);
  } catch {
    /* সার্ভার নিজেও সময় শেষে বন্ধ করে */
  }
}

export interface StartOpts { ctx: CallCtx; refId: string; peerUid?: string; video: boolean; peerName?: string; phone?: string | null }

/** কল করা: অনুমতি → call_start → রিং (ওপাশ ধরলে কথা) */
export async function startCall(o: StartOpts) {
  if (busy()) return;
  reset();
  const my = gen;
  Object.assign(callUi, { phase: 'outgoing', ctx: o.ctx, refId: o.refId, video: o.video, peer: { uid: o.peerUid ?? '', name: o.peerName ?? '' }, asking: true, link: 'connecting' });
  let camOk = false;
  try {
    camOk = await preflight(o.video);
  } catch (e) {
    if (my !== gen) return;
    return finish(null, { error: mediaText(e instanceof MediaError ? e.kind : 'failed') });
  }
  if (my !== gen) return;
  callUi.asking = false;
  let r;
  try {
    r = await callStart({ ctx: o.ctx, refId: o.refId, video: o.video, ...(o.peerUid ? { peerUid: o.peerUid } : {}) });
  } catch (e) {
    if (my !== gen) return;
    const why = refusalOf(e);
    const msg = e instanceof ServerError ? e.message : errorText(e);
    return finish(null, { error: refusalText(why, why === 'other' || why === 'off' || why === 'not_allowed' || why === 'rate' ? msg : undefined), phone: why === 'off' || why === 'busy' || why === 'not_allowed' ? o.phone ?? null : null });
  }
  if (my !== gen) {
    void callCancel(r.callId).catch(() => {});
    return;
  }
  seen.add(r.callId);
  callUi.callId = r.callId;
  if (r.peer.name || r.peer.uid) callUi.peer = { ...callUi.peer, ...r.peer, name: r.peer.name || callUi.peer?.name || '' };
  startRing('outgoing');
  pollTimer = setInterval(async () => {
    try {
      const st = await callStatus(r.callId);
      if (my !== gen || callUi.phase !== 'outgoing') return;
      if (st.state === 'accepted') becomeActive();
      else if (st.state && st.state !== 'ringing') finish(st.state);
    } catch {
      /* পরে আবার */
    }
  }, timing.pollMs);
  ringTimeout = setTimeout(() => {
    if (my === gen && callUi.phase === 'outgoing') void callCancel(r.callId).catch(() => {}).finally(() => my === gen && finish('missed'));
  }, timing.outgoingMs);
  try {
    await enter(r, camOk, my);
  } catch (e) {
    if (my !== gen) return;
    void callCancel(r.callId).catch(() => {});
    finish('failed', { error: e instanceof MediaError ? mediaText(e.kind) : T.failed() });
  }
}

/** কল এসেছে (SSE বা তালিকা দেখে) */
export function onIncoming(inc: Incoming) {
  if (seen.has(inc.callId)) return;
  seen.add(inc.callId);
  if (busy()) return; // সার্ভার ব্যস্ত ধরে; এখানে চুপ
  reset();
  const my = gen;
  Object.assign(callUi, { phase: 'incoming', callId: inc.callId, ctx: inc.ctx, refId: inc.refId, peer: inc.caller, video: inc.video });
  callUi.ringAudible = startRing('incoming');
  setTitle(true);
  pollTimer = setInterval(async () => {
    try {
      const st = await callStatus(inc.callId);
      if (my === gen && callUi.phase === 'incoming' && st.state && st.state !== 'ringing') finish(st.state);
    } catch {
      /* পরে আবার */
    }
  }, timing.pollMs);
  ringTimeout = setTimeout(() => my === gen && callUi.phase === 'incoming' && finish('missed'), timing.ringMs);
}

/** SSE-র `call_state` */
export function onRemoteState(callId: string, state: CallState) {
  if (callId !== callUi.callId) return;
  if (callUi.phase === 'outgoing' && state === 'accepted') return becomeActive();
  if (state === 'ringing' || state === 'accepted') return;
  if (busy()) finish(state);
}

/** কল ধরা: অনুমতি → call_accept → যুক্ত */
export async function accept() {
  if (callUi.phase !== 'incoming' || !callUi.callId) return;
  const id = callUi.callId;
  clearTimers();
  stopRing();
  setTitle(false);
  callUi.ringAudible = false;
  Object.assign(callUi, { phase: 'joining', asking: true, link: 'connecting' });
  const my = gen;
  let camOk = false;
  try {
    camOk = await preflight(callUi.video);
  } catch (e) {
    if (my !== gen) return;
    void callDecline(id).catch(() => {});
    return finish(null, { error: mediaText(e instanceof MediaError ? e.kind : 'failed') });
  }
  if (my !== gen) return;
  callUi.asking = false;
  try {
    const g = await callAccept(id);
    if (my !== gen) return;
    await enter(g, camOk, my);
    if (my !== gen) return;
    becomeActive();
  } catch (e) {
    if (my !== gen) return;
    if (e instanceof ServerError) return finish(null, { error: e.message });
    void callEnd(id).catch(() => {});
    finish('failed', { error: e instanceof MediaError ? mediaText(e.kind) : T.failed() });
  }
}

export async function decline() {
  if (callUi.phase !== 'incoming' || !callUi.callId) return;
  const id = callUi.callId;
  reset();
  try {
    await callDecline(id);
  } catch {
    /* সময় শেষে নিজেই মিস হবে */
  }
}

/** রেখে দেওয়া (কল করার সময় = বাতিল, কথার সময় = শেষ; অনলাইন অ্যাপয়েন্টমেন্টে শুধু বেরিয়ে যাওয়া) */
export async function hangUp() {
  if (!busy()) return;
  const appt = callUi.appt;
  const phase = callUi.phase;
  await endOnServer();
  if (appt) return finish(null, { error: null, keep: false });
  finish(phase === 'outgoing' ? 'cancelled' : 'ended');
}

/** অনলাইন অ্যাপয়েন্টমেন্ট: "ভিডিও কলে যুক্ত হোন" — অপেক্ষার ঘর, অন্যজন এলে কথা */
export async function joinAppointment(o: { appointmentId: string; role: 'patient' | 'provider'; peerName: string }) {
  if (busy()) return;
  reset();
  const my = gen;
  Object.assign(callUi, { phase: 'joining', ctx: 'appointment', refId: o.appointmentId, video: true, appt: { id: o.appointmentId, role: o.role }, peer: { uid: '', name: o.peerName }, asking: true, link: 'connecting' });
  if (callUi.apptReady?.appointmentId === o.appointmentId) callUi.apptReady = null;
  let camOk = false;
  try {
    camOk = await preflight(true);
  } catch (e) {
    if (my !== gen) return;
    return finish(null, { error: mediaText(e instanceof MediaError ? e.kind : 'failed') });
  }
  if (my !== gen) return;
  callUi.asking = false;
  try {
    const g = await apptJoin(o.appointmentId);
    if (my !== gen) return;
    callUi.callId = g.callId || null;
    if (g.callId) seen.add(g.callId);
    await enter(g, camOk, my);
    if (my !== gen) return;
    becomeActive();
  } catch (e) {
    if (my !== gen) return;
    finish(null, { error: e instanceof MediaError ? mediaText(e.kind) : errorText(e) });
  }
}

// ---------- কথার সময়ের বোতাম ----------
async function safe(f: () => Promise<void>) {
  try {
    await f();
  } catch (e) {
    callUi.note = e instanceof MediaError ? mediaText(e.kind) : errorText(e);
  }
}
export const toggleMic = () =>
  safe(async () => {
    const on = !callUi.mic;
    await media?.setMic(on);
    callUi.mic = on;
  });
export const toggleCam = () =>
  safe(async () => {
    const on = !callUi.cam;
    if (on && callUi.audioOnly) {
      await media?.setAudioOnly(false);
      callUi.audioOnly = false;
    }
    await media?.setCam(on);
    callUi.cam = on;
    if (on) callUi.note = null;
  });
export const flipCamera = () => safe(async () => void (await media?.flip()));
export const setAudioOnly = (on: boolean) =>
  safe(async () => {
    await media?.setAudioOnly(on);
    callUi.audioOnly = on;
    if (on) callUi.cam = false;
  });
export const listDevices = () => media?.devices() ?? Promise.resolve({ audioinput: [], videoinput: [], audiooutput: [] });
export const useDevice = (kind: MediaDeviceKind, id: string) => safe(async () => void (await media?.useDevice(kind, id)));
export const startAudio = () => safe(async () => void (await media?.startAudio()));
export function attachVideo(kind: 'local' | 'remote', el: HTMLVideoElement | null) {
  if (kind === 'local') media?.attachLocal(el);
  else media?.attachRemote(el);
}

/** ডাক্তার অনলাইন অ্যাপয়েন্টমেন্টের কলে এলেন (appt_call_ready) */
export function onApptReady(appointmentId: string) {
  if (callUi.appt?.id === appointmentId) return;
  callUi.apptReady = { appointmentId };
}
export const clearApptReady = () => (callUi.apptReady = null);

/** লগআউট হলে সব বন্ধ */
export function resetCalls() {
  if (busy() && callUi.callId) void endOnServer();
  reset();
  callUi.apptReady = null;
  seen.clear();
}
export const inCallText = () => tr('You are already in a call', 'আপনি এখন একটা কলে আছেন'); // bn-ok
