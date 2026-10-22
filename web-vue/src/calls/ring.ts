// রিং-এর শব্দ (WebAudio-তে বানানো, আলাদা ফাইল লাগে না)। ব্রাউজার ব্যবহারকারী পাতায় একবার না চাপলে শব্দ চালাতে দেয় না —
// তাই আগে চাপ পড়েছে কি না দেখে তবেই বাজে; না পড়লে শুধু ব্যানার (আর ট্যাবের শিরোনাম)।
let touched = false;
let ctx: AudioContext | null = null;
let timer: ReturnType<typeof setInterval> | null = null;

/** পাতায় অন্তত একবার চাপ/কী পড়েছে কি না */
export function userActivated(): boolean {
  const ua = (navigator as Navigator & { userActivation?: { hasBeenActive: boolean } }).userActivation;
  return touched || !!ua?.hasBeenActive;
}
export function watchActivation() {
  const on = () => {
    touched = true;
    window.removeEventListener('pointerdown', on, true);
    window.removeEventListener('keydown', on, true);
  };
  window.addEventListener('pointerdown', on, true);
  window.addEventListener('keydown', on, true);
}

function audio(): AudioContext | null {
  try {
    const AC = window.AudioContext ?? (window as any).webkitAudioContext;
    if (!AC) return null;
    ctx ??= new AC();
    if (ctx.state === 'suspended') void ctx.resume();
    return ctx;
  } catch {
    return null;
  }
}

function beep(c: AudioContext, at: number, freqs: number[], len: number, vol: number) {
  const g = c.createGain();
  g.gain.setValueAtTime(0, at);
  g.gain.linearRampToValueAtTime(vol, at + 0.02);
  g.gain.setValueAtTime(vol, at + len - 0.05);
  g.gain.linearRampToValueAtTime(0, at + len);
  g.connect(c.destination);
  for (const f of freqs) {
    const o = c.createOscillator();
    o.frequency.value = f;
    o.connect(g);
    o.start(at);
    o.stop(at + len);
  }
}

/** কল আসছে (জোরে, দুই টোন) বা কল যাচ্ছে (নরম রিংব্যাক) — stopRing() পর্যন্ত */
export function startRing(kind: 'incoming' | 'outgoing'): boolean {
  stopRing();
  if (!userActivated()) return false;
  const c = audio();
  if (!c) return false;
  const play = () => {
    const t = c.currentTime + 0.02;
    if (kind === 'incoming') {
      beep(c, t, [880, 1046], 0.4, 0.18);
      beep(c, t + 0.6, [880, 1046], 0.4, 0.18);
    } else {
      beep(c, t, [440, 480], 1.2, 0.06);
    }
  };
  play();
  timer = setInterval(play, kind === 'incoming' ? 2500 : 4000);
  try {
    if (kind === 'incoming') navigator.vibrate?.([400, 200, 400]);
  } catch {
    /* নেই */
  }
  return true;
}
export function stopRing() {
  if (timer) clearInterval(timer);
  timer = null;
  try {
    navigator.vibrate?.(0);
  } catch {
    /* নেই */
  }
}
export const ringing = () => timer != null;
