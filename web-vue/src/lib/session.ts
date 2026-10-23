// সেশন: লগইন, প্রোফাইল, "me" (ব্যাজ/অ্যাডমিন/না পড়া), আমার কাজ, এলাকা, আর লগইন-গেট
// (অ্যাপের LoginGate: যে কাজে অ্যাকাউন্ট লাগে সেখানে লগইন/তথ্য চেয়ে নিয়ে কাজটাই চালিয়ে দেয়)।
// Vue: React-এর SessionProvider-এর বদলে একটাই reactive অবজেক্ট (initSession() main.ts-এ একবার ডাকা হয়)।
import { markRaw, reactive, watch } from 'vue';
import { onAuthStateChanged, signInWithCustomToken, signOut as fbSignOut, type User } from 'firebase/auth';
import { auth } from './firebase';
import { call, onAccountDeactivated, onAccountDisabled, postPublic } from './api';
import { parseAccount, type AccountInfo } from './account';
import { emptyProfile, hasLocation, isComplete, isDonor, loadProfileAndAccount, saveLang, saveLocation, type Profile } from './profile';
import { store } from './store';
import { getLang, tr, useLang } from './i18n';
import { setCareMode } from './care';
import { roundCoord } from './geo';
import { TEST_MODE, testUser } from './testmode';
import { queryClient } from './query';
import { authUid } from './uid';

export interface CommunityMe {
  badge: { type: string; status: string; regNo?: string; workplace?: string; changePending?: boolean; statusReason?: string; docsRequestNote?: string; [k: string]: unknown } | null;
  isAdmin: boolean;
  isOwner: boolean;
  phoneCheck: boolean;
  phoneVerified: boolean;
  unread: number;
}

export interface WorkSummary {
  ambulances: any[];
  pharmacy: any | null;
  bloodBanks: any[];
  lab: any | null;
  /** অনুমোদিত পেশাদার ব্যাজ (চেম্বার বা বাসায় সেবা) */
  professional: string | null;
  /** যেসব ডাক্তারের সহকারী (প্রেসক্রিপশন/রোগী এন্ট্রি) */
  assisting: { doctorUid: string; name: string; degrees: string }[];
}
// ফিজিওথেরাপিস্ট/সাইকোলজিস্ট (lib/practice.ts): অনুমোদিত ব্যাজ থাকলে নিজের চেম্বার — সার্ভার সুইচ ছাড়া অনুমোদন দেয় না
export const PROVIDER_TYPES = ['doctor', 'nurse', 'nutritionist', 'trainer', 'physio', 'psychologist'];
const emptyWork = (): WorkSummary => ({ ambulances: [], pharmacy: null, bloodBanks: [], lab: null, professional: null, assisting: [] });
export const workHas = (w: WorkSummary) => w.ambulances.length > 0 || !!w.pharmacy || w.bloodBanks.length > 0 || !!w.lab || !!w.professional || w.assisting.length > 0;

/** এলাকা (রক্তের তালিকা, কাছের জিনিস): লগইন থাকলে প্রোফাইলের, নইলে এই ব্রাউজারের */
export interface Place { lat: number; lng: number; placeName: string; source: 'gps' | 'district' }

interface Pending { reason: string; donor: boolean; then: () => void }

export interface Session {
  ready: boolean;
  user: User | null;
  readonly uid: string | null;
  readonly email: string;
  readonly guest: boolean;
  profile: Profile | null;
  profileLoaded: boolean;
  reloadProfile: () => Promise<Profile | null>;
  setProfile: (p: Profile) => void;
  me: CommunityMe | null;
  reloadMe: () => Promise<void>;
  work: WorkSummary;
  workLoaded: boolean;
  workError: string | null;
  refreshWork: () => Promise<void>;
  readonly place: Place | null;
  setPlace: (p: Place) => Promise<void>;
  sendCode: (email: string) => Promise<void>;
  signIn: (email: string, code: string) => Promise<{ restored: boolean }>;
  signOut: () => Promise<void>;
  /** অ্যাকাউন্ট লাগে এমন কাজ: সব থাকলে সাথে সাথে, নইলে লগইন/তথ্য চেয়ে তারপর */
  requireLogin: (reason: string, then: () => void, opts?: { donor?: boolean }) => void;
  login: () => void;
  pending: Pending | null;
  finishPending: (ok: boolean) => void;
  disabledMessage: string | null;
  clearDisabled: () => void;
  /** অ্যাকাউন্টের অবস্থা (profile_get-এর `account`); null = জানা নেই (চালু ধরে নেওয়া) */
  account: AccountInfo | null;
  /** লগইন করা আর অ্যাকাউন্ট সাময়িক বন্ধ — পুরো ওয়েব আটকে "আবার চালু করুন" */
  readonly deactivated: boolean;
  /** account_reactivate, তারপর সব তথ্য নতুন করে */
  reactivate: () => Promise<void>;
}

const GUEST_AREA = 'lf.guestArea';

let rids: { email: string; list: string[] } = { email: '', list: [] };
let uidNow: string | null = null;
/** শেষ কবে আবার চালু হলো — এর আগে পাঠানো অনুরোধের পুরোনো 423 উত্তর আর আটকায় না */
let reactivatedAt = 0;
let guestPlace: Place | null = store.getJson<Place>(GUEST_AREA);

function setUser(u: User | null) {
  uidNow = u?.uid ?? null;
  authUid.value = uidNow;
  S.user = u ? markRaw(u) : null;
}

async function reloadProfile(): Promise<Profile | null> {
  if (TEST_MODE && testUser()) return { ...emptyProfile(), ...(window.__lfTest?.profile ?? {}) } as Profile;
  const u = auth.currentUser;
  if (!u) return null;
  try {
    const { profile: p, account } = await loadProfileAndAccount(u.uid);
    if (uidNow !== u.uid) return null;
    S.account = account;
    S.profile = p ?? emptyProfile();
    S.profileLoaded = true;
    return p ?? emptyProfile();
  } catch {
    S.profileLoaded = true;
    return null;
  }
}

async function reloadMe() {
  if (!auth.currentUser && !(TEST_MODE && testUser())) return;
  try {
    S.me = await call<CommunityMe>('me');
  } catch {
    /* পরে আবার */
  }
}

async function refreshWork() {
  const u = auth.currentUser ?? (TEST_MODE ? testUser() : null);
  if (!u) {
    S.work = emptyWork();
    S.workLoaded = false;
    return;
  }
  const twice = async <T,>(f: () => Promise<T>) => {
    try {
      return await f();
    } catch {
      await new Promise((r) => setTimeout(r, 1500));
      return f();
    }
  };
  const [a, p, b, l, m, x] = await Promise.allSettled([
    twice(() => call('ambulance_mine')),
    twice(() => call('pharmacy_me')),
    twice(() => call('bloodbank_mine')),
    twice(() => call('lab_me')),
    twice(() => call<CommunityMe>('me')),
    twice(() => call<{ items?: WorkSummary['assisting'] }>('rx_assisting')),
  ]);
  if (uidNow !== u.uid) return;
  if (m.status === 'fulfilled') S.me = m.value;
  const old = S.work;
  const badge = m.status === 'fulfilled' ? m.value.badge : null;
  S.work = {
    ambulances: a.status === 'fulfilled' ? a.value.items ?? [] : old.ambulances,
    pharmacy: p.status === 'fulfilled' ? p.value.pharmacy ?? null : old.pharmacy,
    bloodBanks: b.status === 'fulfilled' ? b.value.items ?? [] : old.bloodBanks,
    lab: l.status === 'fulfilled' ? l.value.lab ?? null : old.lab,
    professional: badge && badge.status === 'approved' && PROVIDER_TYPES.includes(badge.type) ? badge.type : m.status === 'fulfilled' ? null : old.professional,
    assisting: x.status === 'fulfilled' ? x.value.items ?? [] : old.assisting,
  };
  const failed = [a, p, b, l].filter((r) => r.status === 'rejected') as PromiseRejectedResult[];
  if (!failed.length) {
    S.workLoaded = true;
    S.workError = null;
  } else S.workError = failed.map((f) => (f.reason instanceof Error ? f.reason.message : '?')).join(' · ');
}

async function sendCode(email: string) {
  const r = await postPublic<{ rid?: string }>('/api/auth/send-code', { email });
  if (rids.email !== email) rids = { email, list: [] };
  if (r.rid) {
    rids.list.push(r.rid);
    while (rids.list.length > 3) rids.list.shift();
  }
}

async function signIn(email: string, code: string) {
  const list = rids.email === email ? rids.list : [];
  const r = await postPublic<{ token?: string; restored?: boolean; account?: unknown }>('/api/auth/verify-code', { email, code, ...(list.length ? { rids: list } : {}) });
  if (!r.token) throw new Error(tr('Login failed, try again', 'লগইন হয়নি, আবার চেষ্টা করুন'));
  // লগইনের উত্তরেই অ্যাকাউন্টের অবস্থা: সাময়িক বন্ধ হলে প্রোফাইল আসার আগেই "আবার চালু করুন" পর্দা
  S.account = parseAccount(r.account) ?? S.account;
  await signInWithCustomToken(auth, r.token);
  queryClient.invalidateQueries();
  return { restored: !!r.restored };
}

async function reactivate() {
  const r = await call<{ ok?: boolean; account?: unknown }>('account_reactivate');
  reactivatedAt = Date.now();
  const a = parseAccount(r?.account);
  S.account = a && a.state !== 'deactivated' ? a : { state: 'active' };
  queryClient.invalidateQueries();
  await reloadProfile();
  refreshWork();
}

function clearUser() {
  S.account = null;
  S.profile = null;
  S.profileLoaded = false;
  S.me = null;
  S.work = emptyWork();
  S.workLoaded = false;
}

async function signOut() {
  setCareMode(null);
  // পরীক্ষার বিল্ড: নকল লগইনও ছাড়া (মোছা/সাময়িক বন্ধের পর লগআউট দেখা যায়)
  if (TEST_MODE && window.__lfTest?.user) {
    delete window.__lfTest.user;
    setUser(null);
    clearUser();
    queryClient.clear();
    return;
  }
  await fbSignOut(auth);
  queryClient.clear();
}

async function setPlace(p: Place) {
  const v = { ...p, lat: roundCoord(p.lat), lng: roundCoord(p.lng) };
  store.setJson(GUEST_AREA, v);
  guestPlace = v;
  S.guestPlaceTick++;
  const u = auth.currentUser;
  if (u) {
    await saveLocation(u.uid, v.lat, v.lng, v.placeName, v.source);
    await reloadProfile();
  }
}

function requireLogin(reason: string, then: () => void, opts?: { donor?: boolean }) {
  const donor = !!opts?.donor;
  if ((auth.currentUser || (TEST_MODE && testUser())) && isComplete(S.profile) && (!donor || isDonor(S.profile))) then();
  else S.pending = markRaw({ reason, donor, then });
}

function finishPending(ok: boolean) {
  const p = S.pending;
  S.pending = null;
  if (ok && p) setTimeout(p.then, 0);
}

const S = reactive({
  ready: false,
  user: null as User | null,
  get uid(): string | null {
    return this.user?.uid ?? null;
  },
  get email(): string {
    return this.user?.email ?? '';
  },
  get guest(): boolean {
    return !this.user;
  },
  profile: null as Profile | null,
  profileLoaded: false,
  reloadProfile,
  setProfile: (p: Profile) => {
    S.profile = p;
  },
  me: null as CommunityMe | null,
  reloadMe,
  work: emptyWork(),
  workLoaded: false,
  workError: null as string | null,
  refreshWork,
  /** guestPlace (সাধারণ চলক) বদলালে place আবার হিসাব করাতে */
  guestPlaceTick: 0,
  get place(): Place | null {
    void this.guestPlaceTick;
    const profile = this.profile;
    if (this.user && profile && hasLocation(profile)) {
      return { lat: profile.lat!, lng: profile.lng!, placeName: profile.placeName ?? '', source: profile.locationSource === 'gps' ? 'gps' : 'district' };
    }
    return guestPlace;
  },
  setPlace,
  sendCode,
  signIn,
  signOut,
  requireLogin,
  login: () => {
    S.pending = markRaw({ reason: '', donor: false, then: () => {} });
  },
  pending: null as Pending | null,
  finishPending,
  disabledMessage: null as string | null,
  clearDisabled: () => {
    S.disabledMessage = null;
  },
  account: null as AccountInfo | null,
  get deactivated(): boolean {
    return !!this.user && this.account?.state === 'deactivated';
  },
  reactivate,
});

export function useSession(): Session {
  return S as unknown as Session;
}

let started = false;
/** একবার (main.ts): Firebase লগইনের অবস্থা শোনা, ভাষা বদল, অ্যাকাউন্ট বন্ধের খবর */
export function initSession() {
  if (started) return;
  started = true;
  // পরীক্ষার বিল্ড: আসল Firebase ছাড়া লগইন করা অবস্থা
  const tu = TEST_MODE ? testUser() : null;
  if (tu) {
    setUser(tu as unknown as User);
    S.profile = { ...emptyProfile(), ...(window.__lfTest?.profile ?? {}) } as Profile;
    S.profileLoaded = true;
    S.account = parseAccount(window.__lfTest?.account);
    S.ready = true;
    refreshWork();
  } else {
    onAuthStateChanged(auth, async (u) => {
      setUser(u);
      setCareMode(null);
      if (!u) {
        clearUser();
        S.ready = true;
        return;
      }
      S.ready = true;
      const p = await reloadProfile();
      // অতিথি অবস্থায় বাছা এলাকা: প্রোফাইলে এলাকা না থাকলে সেটাই বসে
      const gp = store.getJson<Place>(GUEST_AREA);
      if (p && !hasLocation(p) && gp) {
        try {
          await saveLocation(u.uid, gp.lat, gp.lng, gp.placeName, gp.source);
          await reloadProfile();
        } catch {
          /* পরে */
        }
      }
      saveLang(u.uid, getLang()).catch(() => {});
      refreshWork();
    });
  }

  // ভাষা বদলালে সার্ভারের নোটিফিকেশনের ভাষাও; আর আগের ভাষার ক্যাশ বাদ
  watch(useLang(), (lang) => {
    if (auth.currentUser) saveLang(auth.currentUser.uid, lang).catch(() => {});
    queryClient.invalidateQueries();
  });

  // সাময়িক বন্ধ অ্যাকাউন্টে যেকোনো কাজ 423 দিলে: পুরো ওয়েব আটকে "আবার চালু করুন" (লগইনের ধাপ খোলা থাকলে বন্ধ)
  onAccountDeactivated((startedAt) => {
    if (!S.user || startedAt < reactivatedAt) return;
    S.account = { ...(S.account ?? {}), state: 'deactivated' };
  });
  watch(
    () => S.deactivated,
    (d) => {
      if (d && S.pending) S.pending = null;
    },
  );

  onAccountDisabled((msg) => {
    S.disabledMessage = msg || tr('This account is turned off', 'এই অ্যাকাউন্ট বন্ধ করা আছে');
    fbSignOut(auth).catch(() => {});
  });
}
