// অ্যাপে কল (backend/docs/CALLS.md) — নকল উত্তর। অবস্থা রাখে না; ধাপে ধাপে বদল e2e/calls.spec.ts নিজে page.route দিয়ে দেয়।
const MIN = 60 * 1000;
const now = Date.now();

export default {
  call_config: () => ({ ok: true, enabled: true, url: 'wss://lifolo.com/rtc-test' }),
  // যোগাযোগের বোতাম: সবই চলে (আলাদা অবস্থা পরীক্ষা নিজে দেয়)
  contact_can: (b) => ({ ok: true, items: (b.items || []).map((i) => ({ ctx: i.ctx, refId: i.refId, peerUid: i.peerUid || 'peer1', canCall: true, canVideo: true, canMessage: true })) }),
  call_start: (b) => ({ ok: true, callId: 'c_new', room: 'c_c_new', url: 'wss://lifolo.com/rtc-test', token: 'tok-caller', video: b.video === true, peer: { uid: b.peerUid || 'peer1', name: 'সুমাইয়া আক্তার' } }),
  call_accept: (b) => ({ ok: true, callId: b.callId, room: `c_${b.callId}`, url: 'wss://lifolo.com/rtc-test', token: 'tok-callee' }),
  call_upgrade: () => ({ ok: true }),
  call_upgrade_accept: (b) => ({ ok: true, callId: b.callId, room: `c_${b.callId}`, url: 'wss://lifolo.com/rtc-test', token: 'tok-video', video: true }),
  call_upgrade_decline: () => ({ ok: true }),
  call_block: () => ({ ok: true }),
  call_unblock: () => ({ ok: true }),
  call_report: () => ({ ok: true }),
  call_blocks: () => ({ ok: true, items: [{ uid: 'b1', name: 'অচেনা নম্বর', blockedAt: now - 2 * 86400000 }] }),
  call_decline: () => ({ ok: true }),
  call_cancel: () => ({ ok: true }),
  call_end: () => ({ ok: true }),
  call_status: () => ({ state: 'ringing' }),
  call_history: (b) =>
    b.before
      ? { items: [] }
      : {
          items: [
            { callId: 'h1', ctx: 'blood', refId: 'm1', peer: { uid: 'd1', name: 'রাকিব হাসান' }, video: false, state: 'ended', startedAt: now - 30 * MIN, durationSec: 185, dir: 'out' },
            { callId: 'h2', ctx: 'appointment', refId: 'a1', peer: { uid: 'p1', name: 'ডা. মাহমুদা খাতুন' }, video: true, state: 'missed', startedAt: now - 3 * 60 * MIN, durationSec: 0, dir: 'in' },
            { callId: 'h3', ctx: 'ambulance', refId: 'ar1', peer: { uid: 'dr1', name: 'জনসেবা অ্যাম্বুলেন্স' }, video: false, state: 'declined', startedAt: now - 26 * 60 * MIN, durationSec: 0, dir: 'out' },
          ],
          next: now - 26 * 60 * MIN,
        },
  appt_join: (b) => ({ ok: true, callId: `ap_${b.appointmentId}`, room: `c_ap_${b.appointmentId}`, url: 'wss://lifolo.com/rtc-test', token: 'tok-appt' }),
  // ওয়েবের SSE — নকল সার্ভারে নেই (ওয়েব তখন তালিকা দেখে); পরীক্ষা নিজে page.route দিয়ে ঘটনা পাঠায়
  '/api/events': () => ({ __status: 503, body: { error: 'no sse in mock' } }),
};
