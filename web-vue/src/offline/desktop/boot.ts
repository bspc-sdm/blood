// ডেস্কটপের শুরু: অফলাইন ইঞ্জিন বসানো, পিন লক আর ৭ দিনের হিসাব, ট্রে/নোটিফিকেশন/আপডেট, সেটিংসের পাতা।
// ওয়েবের কোড বদলানো হয় না — শুধু `installOffline()` আর `router.addRoute()`; লক/অবস্থার পর্দা আলাদা ছোট Vue অ্যাপে।
import { createApp, watch } from 'vue';
import { listen } from '@tauri-apps/api/event';
import { router } from '@/router';
import { useSession } from '@/lib/session';
import { toast } from '@/ui/toast';
import { vFocus } from '@/ui/composables';
import { installOffline, markDesktopShell } from '..';
import { OfflineEngine, hashName } from '../engine/engine';
import { randomCode } from '../engine/crypt';
import { desktopFeatureOn, refreshDesktopFeature } from '../feature';
import { T } from '../text';
import type { BizKind, OfflineStore } from '../types';
import { engineApi } from './api';
import { Grace } from './grace';
import { appInfo, closeAllDbs, dbCipher, deviceName, dropDb, forgetCipher, openDb, secrets, secretsWipe } from './native';
import { onNet } from './net';
import { listenEvents, notify } from './notify';
import { PinLock } from './pin';
import { printInvoice } from './printing';
import { startUpdateChecks, checkUpdate } from './update';
import Overlay from './Overlay.vue';

const s = useSession();

export const grace = new Grace(secrets);
export const pin = new PinLock(secrets, wipeAll);

const REG_KEY = 'lf.desk.reg';
function registeredList(): string[] {
  try {
    return JSON.parse(localStorage.getItem(REG_KEY) ?? '[]') as string[];
  } catch {
    return [];
  }
}
function markRegistered(uid: string) {
  const h = hashName(uid);
  const l = registeredList();
  if (!l.includes(h)) {
    try {
      localStorage.setItem(REG_KEY, JSON.stringify([...l, h]));
    } catch {
      /* পরের বার ফিচার সুইচ দেখে */
    }
  }
}

let online = navigator.onLine;

export const engine = new OfflineEngine({
  openDb,
  dropDb,
  cipher: dbCipher,
  secrets,
  api: engineApi,
  uid: () => s.uid,
  deviceName,
  now: () => Date.now(),
  uuid: () => crypto.randomUUID(),
  randomCode,
  online: () => online,
  gate: () => (pin.state.locked ? 'locked' : grace.tick()),
  role: () => pin.role(),
  canOwner: () => pin.canOwner(),
  requireOwner: () => pin.requireOwner(),
  print: printInvoice,
  bizInfo: (kind, bizId) => bizInfo(kind, bizId),
  setTimer: (fn, ms) => setTimeout(fn, ms),
  clearTimer: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
  onRegistered: (ok) => {
    if (ok && s.uid) markRegistered(s.uid);
    if (!ok) toast(T.featureOff());
  },
  onWiped: () => {
    toast(T.revoked());
  },
});

function bizInfo(kind: BizKind, bizId: string): { name: string; phone?: string; address?: string } | null {
  const w = s.work as any;
  const pick = (x: any) => (x && (!x.id || String(x.id) === bizId) ? { name: String(x.name ?? ''), phone: x.phone ? String(x.phone) : undefined, address: x.address ? String(x.address) : undefined } : null);
  if (kind === 'pharmacy') return pick(w?.pharmacy);
  if (kind === 'lab') return pick(w?.lab);
  const p = s.profile as any;
  return p?.name ? { name: String(p.name) } : null;
}

/** ডেস্কটপের অফলাইন স্টোর — isDesktop(): ফিচার সুইচ চালু, বা এই কম্পিউটার আগেই নিবন্ধিত (সুইচ বন্ধ হলেও বিক্রি হারায় না) */
const store: OfflineStore = {
  isDesktop: () => !!s.uid && (desktopFeatureOn() || registeredList().includes(hashName(s.uid))),
  ready: (k, b) => engine.ready(k, b),
  items: (q) => engine.items(q),
  labPrices: () => engine.labPrices(),
  chamberDay: (d) => engine.chamberDay(d),
  invoices: (q) => engine.invoices(q),
  record: (op) => engine.record(op),
  pending: () => engine.pending(),
  syncNow: () => engine.syncNow(),
  onChange: (cb) => engine.onChange(cb),
  print: (inv, o) => engine.print(inv, o),
  role: () => engine.role(),
  requireOwner: () => engine.requireOwner(),
};

/** ১০ বার ভুল পিন: সব অফলাইন তথ্য আর চাবি মুছে লগআউট (নিরাপত্তা পর্যালোচনা I4) */
async function wipeAll() {
  await engine.close().catch(() => {});
  await closeAllDbs().catch(() => {});
  await secretsWipe().catch(() => {});
  forgetCipher();
  try {
    localStorage.removeItem(REG_KEY);
  } catch {
    /* কিছু নেই */
  }
  pin.unload();
  grace.unload();
  await s.signOut().catch(() => {});
  toast(T.wiped());
}

/** ব্যবসা আছে (ফার্মেসি, ল্যাব, চেম্বার, সহকারী) — তাহলে পিন বাধ্যতামূলক */
export function hasBusiness(): boolean {
  const w = s.work;
  return !!(w.pharmacy || w.lab || w.professional || w.assisting.length);
}

let stopEvents: (() => void) | null = null;

export async function bootDesktop() {
  markDesktopShell();
  installOffline(store);

  // ডেস্কটপের নিজের পাতা
  router.addRoute({ path: '/desktop/settings', component: () => import('./DesktopSettings.vue') });

  // লক/অবস্থার পর্দা
  const host = document.createElement('div');
  host.id = 'lf-desk-overlay';
  document.body.append(host);
  createApp(Overlay).directive('focus', vFocus).mount(host);

  // সার্ভারে পৌঁছানো = অনলাইন; লগইন করা ডাক = ৭ দিনের যাচাই
  onNet((e) => {
    online = e.reached;
    if (e.reached && e.authed && e.status !== 401 && e.status !== 403) grace.online(e.serverDate);
  });
  window.addEventListener('online', () => {
    online = true;
    void engine.syncNow();
  });
  window.addEventListener('offline', () => {
    online = false;
  });

  // কাজ চলছে কি না (১০ মিনিটের লক)
  let lastMove = 0;
  const act = () => pin.activity();
  for (const ev of ['pointerdown', 'keydown', 'wheel', 'touchstart']) window.addEventListener(ev, act, { capture: true, passive: true });
  window.addEventListener(
    'mousemove',
    () => {
      const n = Date.now();
      if (n - lastMove > 5000) {
        lastMove = n;
        act();
      }
    },
    { passive: true },
  );
  setInterval(() => {
    pin.idleCheck();
    grace.tick();
  }, 15_000);

  // লগইন বদল: আগের ব্যবহারকারীর সব বন্ধ, নতুনের পিন আর ৭ দিনের হিসাব
  watch(
    () => s.uid,
    async (uid, old) => {
      if (uid === old) return;
      stopEvents?.();
      stopEvents = null;
      await engine.close().catch(() => {});
      pin.unload();
      grace.unload();
      if (!uid) return;
      const key = hashName(uid);
      await Promise.all([pin.load(key), grace.load(key)]);
      stopEvents = listenEvents(() => void refreshDesktopFeature());
    },
    { immediate: true },
  );

  // ট্রে
  await listen<string>('desktop://menu', (e) => {
    void router.push('/desktop/settings');
    if (e.payload === 'update') void checkUpdate(true);
  });
  let toldTray = false;
  await listen('desktop://hidden', () => {
    if (toldTray) return;
    toldTray = true;
    void notify('Lifolo', T.inTray());
  });

  startUpdateChecks();

  // Sentry: ডেস্কটপের ঘটনা আলাদা চেনা যায় (DSN ওয়েবেরটাই, বিল্ডের সময়)
  void appInfo()
    .then((info) =>
      import('@sentry/vue').then((Sentry) => {
        Sentry.setTag('app', 'desktop');
        Sentry.setTag('desktop_version', info.version);
      }),
    )
    .catch(() => {});
}
