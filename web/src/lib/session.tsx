// সেশন: লগইন, প্রোফাইল, "me" (ব্যাজ/অ্যাডমিন/না পড়া), আমার কাজ, এলাকা, আর লগইন-গেট
// (অ্যাপের LoginGate: যে কাজে অ্যাকাউন্ট লাগে সেখানে লগইন/তথ্য চেয়ে নিয়ে কাজটাই চালিয়ে দেয়)।
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { onAuthStateChanged, signInWithCustomToken, signOut as fbSignOut, type User } from 'firebase/auth';
import { useQueryClient } from '@tanstack/react-query';
import { auth } from './firebase';
import { call, onAccountDisabled, postPublic } from './api';
import { emptyProfile, hasLocation, isComplete, isDonor, loadProfile, saveLang, saveLocation, type Profile } from './profile';
import { store } from './store';
import { getLang, tr, useLang } from './i18n';
import { setCareMode } from './care';
import { roundCoord } from './geo';
import { TEST_MODE, testUser } from './testmode';

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
}
export const PROVIDER_TYPES = ['doctor', 'nurse', 'nutritionist', 'trainer'];
const emptyWork = (): WorkSummary => ({ ambulances: [], pharmacy: null, bloodBanks: [], lab: null, professional: null });
export const workHas = (w: WorkSummary) => w.ambulances.length > 0 || !!w.pharmacy || w.bloodBanks.length > 0 || !!w.lab || !!w.professional;

/** এলাকা (রক্তের তালিকা, কাছের জিনিস): লগইন থাকলে প্রোফাইলের, নইলে এই ব্রাউজারের */
export interface Place { lat: number; lng: number; placeName: string; source: 'gps' | 'district' }

interface Pending { reason: string; donor: boolean; then: () => void }

interface Session {
  ready: boolean;
  user: User | null;
  uid: string | null;
  email: string;
  guest: boolean;
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
  place: Place | null;
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
}

const Ctx = createContext<Session | null>(null);
export function useSession(): Session {
  const s = useContext(Ctx);
  if (!s) throw new Error('SessionProvider missing');
  return s;
}

const GUEST_AREA = 'lf.guestArea';

export function SessionProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const lang = useLang();
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState<User | null>(null);
  const [profile, setProfileState] = useState<Profile | null>(null);
  const [profileLoaded, setProfileLoaded] = useState(false);
  const [me, setMe] = useState<CommunityMe | null>(null);
  const [work, setWork] = useState<WorkSummary>(emptyWork);
  const [workLoaded, setWorkLoaded] = useState(false);
  const [workError, setWorkError] = useState<string | null>(null);
  const [guestPlace, setGuestPlace] = useState<Place | null>(() => store.getJson<Place>(GUEST_AREA));
  const [pending, setPending] = useState<Pending | null>(null);
  const [disabledMessage, setDisabled] = useState<string | null>(null);
  const rids = useRef<{ email: string; list: string[] }>({ email: '', list: [] });
  const uidRef = useRef<string | null>(null);

  const reloadProfile = useCallback(async () => {
    if (TEST_MODE && testUser()) return { ...emptyProfile(), ...(window.__lfTest?.profile ?? {}) } as Profile;
    const u = auth.currentUser;
    if (!u) return null;
    try {
      const p = await loadProfile(u.uid);
      if (uidRef.current !== u.uid) return null;
      setProfileState(p ?? emptyProfile());
      setProfileLoaded(true);
      return p ?? emptyProfile();
    } catch {
      setProfileLoaded(true);
      return null;
    }
  }, []);

  const reloadMe = useCallback(async () => {
    if (!auth.currentUser && !(TEST_MODE && testUser())) return;
    try {
      const m = await call<CommunityMe>('me');
      setMe(m);
    } catch {
      /* পরে আবার */
    }
  }, []);

  const refreshWork = useCallback(async () => {
    const u = auth.currentUser ?? (TEST_MODE ? testUser() : null);
    if (!u) {
      setWork(emptyWork());
      setWorkLoaded(false);
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
    const [a, p, b, l, m] = await Promise.allSettled([
      twice(() => call('ambulance_mine')),
      twice(() => call('pharmacy_me')),
      twice(() => call('bloodbank_mine')),
      twice(() => call('lab_me')),
      twice(() => call<CommunityMe>('me')),
    ]);
    if (uidRef.current !== u.uid) return;
    if (m.status === 'fulfilled') setMe(m.value);
    setWork((old) => {
      const badge = m.status === 'fulfilled' ? m.value.badge : null;
      return {
        ambulances: a.status === 'fulfilled' ? a.value.items ?? [] : old.ambulances,
        pharmacy: p.status === 'fulfilled' ? p.value.pharmacy ?? null : old.pharmacy,
        bloodBanks: b.status === 'fulfilled' ? b.value.items ?? [] : old.bloodBanks,
        lab: l.status === 'fulfilled' ? l.value.lab ?? null : old.lab,
        professional: badge && badge.status === 'approved' && PROVIDER_TYPES.includes(badge.type) ? badge.type : m.status === 'fulfilled' ? null : old.professional,
      };
    });
    const failed = [a, p, b, l].filter((r) => r.status === 'rejected') as PromiseRejectedResult[];
    if (!failed.length) {
      setWorkLoaded(true);
      setWorkError(null);
    } else setWorkError(failed.map((f) => (f.reason instanceof Error ? f.reason.message : '?')).join(' · '));
  }, []);

  useEffect(() => {
    // পরীক্ষার বিল্ড: আসল Firebase ছাড়া লগইন করা অবস্থা
    const tu = TEST_MODE ? testUser() : null;
    if (tu) {
      uidRef.current = tu.uid;
      setUser(tu as unknown as User);
      setProfileState({ ...emptyProfile(), ...(window.__lfTest?.profile ?? {}) } as Profile);
      setProfileLoaded(true);
      setReady(true);
      refreshWork();
      return;
    }
    return onAuthStateChanged(auth, async (u) => {
      uidRef.current = u?.uid ?? null;
      setUser(u);
      setCareMode(null);
      if (!u) {
        setProfileState(null);
        setProfileLoaded(false);
        setMe(null);
        setWork(emptyWork());
        setWorkLoaded(false);
        setReady(true);
        return;
      }
      setReady(true);
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
  }, [reloadProfile, refreshWork]);

  // ভাষা বদলালে সার্ভারের নোটিফিকেশনের ভাষাও; আর আগের ভাষার ক্যাশ বাদ
  useEffect(() => {
    if (auth.currentUser) saveLang(auth.currentUser.uid, lang).catch(() => {});
    qc.invalidateQueries();
  }, [lang, qc]);

  useEffect(
    () =>
      onAccountDisabled((msg) => {
        setDisabled(msg || tr('This account is turned off', 'এই অ্যাকাউন্ট বন্ধ করা আছে'));
        fbSignOut(auth).catch(() => {});
      }),
    [],
  );

  const sendCode = useCallback(async (email: string) => {
    const r = await postPublic<{ rid?: string }>('/api/auth/send-code', { email });
    if (rids.current.email !== email) rids.current = { email, list: [] };
    if (r.rid) {
      rids.current.list.push(r.rid);
      while (rids.current.list.length > 3) rids.current.list.shift();
    }
  }, []);

  const signIn = useCallback(async (email: string, code: string) => {
    const list = rids.current.email === email ? rids.current.list : [];
    const r = await postPublic<{ token?: string; restored?: boolean }>('/api/auth/verify-code', { email, code, ...(list.length ? { rids: list } : {}) });
    if (!r.token) throw new Error(tr('Login failed, try again', 'লগইন হয়নি, আবার চেষ্টা করুন'));
    await signInWithCustomToken(auth, r.token);
    qc.invalidateQueries();
    return { restored: !!r.restored };
  }, [qc]);

  const signOut = useCallback(async () => {
    setCareMode(null);
    await fbSignOut(auth);
    qc.clear();
  }, [qc]);

  const place: Place | null = useMemo(() => {
    if (user && profile && hasLocation(profile)) {
      return { lat: profile.lat!, lng: profile.lng!, placeName: profile.placeName ?? '', source: profile.locationSource === 'gps' ? 'gps' : 'district' };
    }
    return guestPlace;
  }, [user, profile, guestPlace]);

  const setPlace = useCallback(async (p: Place) => {
    const v = { ...p, lat: roundCoord(p.lat), lng: roundCoord(p.lng) };
    store.setJson(GUEST_AREA, v);
    setGuestPlace(v);
    const u = auth.currentUser;
    if (u) {
      await saveLocation(u.uid, v.lat, v.lng, v.placeName, v.source);
      await reloadProfile();
    }
  }, [reloadProfile]);

  const requireLogin = useCallback((reason: string, then: () => void, opts?: { donor?: boolean }) => {
    const donor = !!opts?.donor;
    if ((auth.currentUser || (TEST_MODE && testUser())) && isComplete(profile) && (!donor || isDonor(profile))) then();
    else setPending({ reason, donor, then });
  }, [profile]);

  const login = useCallback(() => setPending({ reason: '', donor: false, then: () => {} }), []);

  const finishPending = useCallback((ok: boolean) => {
    setPending((p) => {
      if (ok && p) setTimeout(p.then, 0);
      return null;
    });
  }, []);

  const value: Session = {
    ready,
    user,
    uid: user?.uid ?? null,
    email: user?.email ?? '',
    guest: !user,
    profile,
    profileLoaded,
    reloadProfile,
    setProfile: setProfileState,
    me,
    reloadMe,
    work,
    workLoaded,
    workError,
    refreshWork,
    place,
    setPlace,
    sendCode,
    signIn,
    signOut,
    requireLogin,
    login,
    pending,
    finishPending,
    disabledMessage,
    clearDisabled: () => setDisabled(null),
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
