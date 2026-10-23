// স্বাস্থ্য তথ্যের সম্মতি (backend/docs/CONSENT.md), ফিচার সুইচ health_consent।
// লেখা ওয়েবে রাখা হয় না — সার্ভার consent_status-এ পাঠায় (একটাই ভার্সন করা ফাইল, উকিল সেখানেই বদলান)।
// - সার্ভার 428 consent_required দিলে api.ts এখানে জিজ্ঞেস করে: সম্মতির পর্দা → "রাজি আছি" হলে একই কাজ আবার পাঠায়।
// - কেয়ারগিভার রোগীর হয়ে কাজ করলে পর্দা নয়, শুধু "রোগীকে নিজে একবার সম্মতি দিতে হবে"।
// - স্বাস্থ্যের পাতা (প্রেসক্রিপশন, অ্যাপয়েন্টমেন্ট) খুললে একবার জিজ্ঞেস (এই ভার্সনে "এখন না" বললে আর নয়, এই ব্রাউজারে)।
import { onMounted, reactive, watch } from 'vue';
import { call, ServerError, setConsentHandler, type ConsentRequired } from './api';
import { careUid } from './care';
import { featureOn } from './features';
import { store } from './store';
import { testUser } from './testmode';
import { tr } from './i18n';
import { auth } from './firebase';
import { appShell } from '@/offline';

// "এখন না" বললে কাজটা হয় না — পাতায় এই বার্তা
const declined = () => tr('Not saved. This needs your consent for health information.', 'সেভ হয়নি। এর জন্য স্বাস্থ্য তথ্যের সম্মতি লাগে।'); // bn-ok

export const CONSENT_SCOPES = ['health_data', 'data_abroad'] as const;
export type ConsentScope = (typeof CONSENT_SCOPES)[number];

export interface ConsentSection {
  key: string;
  heading: string;
  body: string;
  linkLabel?: string;
  linkUrl?: string;
}
export interface ConsentTextDoc {
  title: string;
  intro: string;
  sections: ConsentSection[];
  scopes: Record<string, string>;
  agreeNote: string;
  agree: string;
  later: string;
  policyLabel?: string;
  policyUrl: string;
  required: string;
  caregiver: string;
  withdrawTitle: string;
  withdrawBody: string;
  withdrawButton: string;
  deleteAccountLabel?: string;
}
export interface ScopeState {
  state: 'granted' | 'outdated' | 'withdrawn' | 'none';
  version: string | null;
  at: number | null;
  source: string | null;
}
export interface ConsentStatus {
  on: boolean;
  version: string;
  current: boolean;
  ask: boolean;
  needed: string[];
  grantedAt: number | null;
  scopes: Record<string, ScopeState>;
  text: ConsentTextDoc;
}

interface Prompt {
  /** 'gate' = একটা কাজ সম্মতির জন্য আটকেছে; 'section' = স্বাস্থ্যের পাতা খোলায়; 'view' = শুধু পুরো লেখা দেখা */
  reason: 'gate' | 'section' | 'view';
  resolve: (agreed: boolean) => void;
}

const state = reactive({
  status: null as ConsentStatus | null,
  prompt: null as Prompt | null,
});
/** পড়ার জন্য (ConsentHost, সেটিংস) */
export const consent: Readonly<typeof state> = state;

/** কোথা থেকে দেওয়া হলো (সার্ভারে রাখা হয়) */
export const consentSource = (): 'web' | 'desktop' => appShell();

const uidNow = () => auth.currentUser?.uid ?? testUser()?.uid ?? null;

export async function loadConsent(): Promise<ConsentStatus> {
  const s = await call<ConsentStatus>('consent_status');
  state.status = s;
  return s;
}

/** "রাজি আছি": দুটো সম্মতি একসাথে (সার্ভারে আলাদা দুটো রেকর্ড) */
export async function grantConsent(scopes: readonly string[] = CONSENT_SCOPES): Promise<ConsentStatus> {
  const version = state.status?.version ?? (await loadConsent()).version;
  try {
    const s = await call<ConsentStatus>('consent_grant', { scopes: [...scopes], version, source: consentSource() });
    state.status = s;
    return s;
  } catch (e) {
    // এর মধ্যে লেখা বদলেছে: নতুন লেখা এনে আবার দেখানো (পুরোনো লেখায় সম্মতি রাখা হয় না)
    if (e instanceof ServerError && e.code === 'consent_outdated') await loadConsent();
    throw e;
  }
}

/** সম্মতি তুলে নেওয়া — কিছুই মোছে না */
export async function withdrawConsent(scope: ConsentScope | 'all' = 'all'): Promise<ConsentStatus> {
  const s = await call<ConsentStatus>('consent_withdraw', { scope, source: consentSource() });
  state.status = s;
  return s;
}

/** পর্দা খুলে উত্তরের অপেক্ষা (true = রাজি আছি) */
export function openConsent(reason: Prompt['reason']): Promise<boolean> {
  state.prompt?.resolve(false);
  return new Promise<boolean>((resolve) => {
    state.prompt = { reason, resolve };
  });
}

/** ConsentHost ডাকে */
export function answerConsent(agreed: boolean) {
  const p = state.prompt;
  state.prompt = null;
  p?.resolve(agreed);
}

const DISMISS_KEY = 'lf.consentLater';
const dismissKey = (uid: string, version: string) => `${uid}|${version}`;
function dismissed(uid: string, version: string): boolean {
  return (store.getJson<string[]>(DISMISS_KEY) || []).includes(dismissKey(uid, version));
}
function rememberDismiss(uid: string, version: string) {
  const list = (store.getJson<string[]>(DISMISS_KEY) || []).filter((k) => !k.startsWith(`${uid}|`));
  store.setJson(DISMISS_KEY, [...list, dismissKey(uid, version)].slice(-10));
}

/**
 * স্বাস্থ্যের পাতা খুললে: সুইচ চালু, নিজের হয়ে (কেয়ারগিভার মোড নয়), সম্মতি নেই/পুরোনো, আর এই লেখায় আগে "এখন না" বলা হয়নি →
 * একবার জিজ্ঞেস। (অ্যাপ খোলার সাথে সাথে নয় — যিনি রক্ত বা অ্যাম্বুলেন্সের জন্য খুলেছেন তাঁকে আটকানো হয় না।)
 */
let asking = false;
export async function askOnHealthSection(): Promise<void> {
  const uid = uidNow();
  if (!uid || careUid() || !featureOn('health_consent') || state.prompt || asking) return;
  asking = true;
  try {
    const s = await loadConsent();
    if (!s.ask || dismissed(uid, s.version) || careUid() || state.prompt) return;
    const agreed = await openConsent('section');
    if (!agreed) rememberDismiss(uid, s.version);
  } catch {
    // ইন্টারনেট নেই: পরের বার
  } finally {
    asking = false;
  }
}

/** স্বাস্থ্যের পাতায়: <script setup> এ useHealthConsentPrompt() */
export function useHealthConsentPrompt() {
  onMounted(() => {
    void askOnHealthSection();
  });
  // সুইচের খবর পরে এলে (প্রথম খোলা)
  const stop = watch(
    () => featureOn('health_consent'),
    (on) => {
      if (on) {
        stop();
        void askOnHealthSection();
      }
    },
  );
}

/** api.ts-এর 428: true হলে api.ts কাজটা আবার পাঠায় */
async function onConsentRequired(info: ConsentRequired): Promise<boolean> {
  if (info.caregiver) return false;
  try {
    await loadConsent();
  } catch {
    return false;
  }
  return openConsent('gate');
}

let wired = false;
/** একবার (main.ts) */
export function initConsent() {
  if (wired) return;
  wired = true;
  setConsentHandler(onConsentRequired, declined);
}
