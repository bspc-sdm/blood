// "ডা." / "Dr." পদবির নিয়ম (backend/docs/PRACTICE.md §৬, ফিচার সুইচ title_guard — "‘ডা.’ পদবির নিয়ম")।
// হাইকোর্টের রায় (২০২০/২০২১, ২০২৫-এ আবার): নামের আগে "ডা."/"Dr." শুধু MBBS/BDS — অ্যাপে ধরন doctor + পদ্ধতি allopathy
// (দাঁতের ডাক্তারও এলোপ্যাথি)। হোমিওপ্যাথি/ইউনানি/আয়ুর্বেদ, নার্স, পুষ্টিবিদ, ফিজিও, সাইকোলজিস্ট … কেউ নয়।
// সার্ভারই আসল পাহারাদার (400 title_not_allowed; পুরোনো নাম দেখানোর সময় ছাঁটা) — এখানে শুধু একই নিয়ম আগে দেখানো।
// উকিল লেখা দেখছেন: এই নিয়মের প্রতিটা লেখা এই ফাইলেই (সার্ভারে practice/TitleRules.kt, অ্যাপে TitleGuard.kt)।
import { featureOn } from './features';
import { tr } from './i18n';

export const titleGuardOn = () => featureOn('title_guard');

// ---------- লেখা (একটাই জায়গা) ----------
export const TITLE_CODE = 'title_not_allowed';
export const TG = {
  error: () => tr('Only MBBS/BDS doctors may write ‘Dr.’ before the name', 'MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না'), // bn-ok
  hint: () => tr("Don't write ‘Dr.’ before your name — your profession is shown automatically", 'নামের আগে ‘ডা.’ লিখবেন না — পেশা নিজে থেকেই দেখানো হবে'), // bn-ok
  /** ব্যাজের আবেদনে: নাম প্রোফাইল থেকে যায় */
  fixInProfile: () => tr('Change the name in Edit profile first', 'আগে প্রোফাইল এডিটে নাম ঠিক করুন'), // bn-ok
  /** খোঁজার "ডাক্তার" ট্যাব যখন সব পদ্ধতির চিকিৎসক এখানে (সুইচ আর চিকিৎসা পদ্ধতি চালু) */
  tabAll: () => tr('Doctors & practitioners', 'ডাক্তার ও চিকিৎসক'), // bn-ok
  /** "কোনো হোমিওপ্যাথিক চিকিৎসক পাওয়া যায়নি" */
  noneFound: (label: string) => tr(`No ${label} found`, `কোনো ${label} পাওয়া যায়নি`), // bn-ok
  female: () => tr('Female', 'নারী'), // bn-ok
  male: () => tr('Male', 'পুরুষ'), // bn-ok
  /** অ্যাডমিন তালিকার নাম */
  adminTitle: () => '‘ডা.’ লেখা নাম', // bn-ok
};

/** অ-এলোপ্যাথিক চিকিৎসকের পেশা ("ডাক্তার"-এর বদলে) */
export function practitionerName(system: string): string | null {
  switch (system) {
    case 'homeopathy': return tr('Homeopathic practitioner', 'হোমিওপ্যাথিক চিকিৎসক'); // bn-ok
    case 'unani': return tr('Unani practitioner', 'ইউনানি চিকিৎসক'); // bn-ok
    case 'ayurveda': return tr('Ayurvedic practitioner', 'আয়ুর্বেদিক চিকিৎসক'); // bn-ok
    default: return null;
  }
}

// ---------- নিয়ম (সার্ভারের TitleRules-এর মতো হুবহু) ----------
/** শুরুতে পদবি: ডা. ডাঃ ডা ডা: ডাক্তার Dr. Dr Dr: DR. Doctor — বড়-ছোট হাতের অক্ষর যেকোনো, পরে ফাঁকা থাকুক বা না থাকুক */
export const TITLE_RE = /^\s*(?:ডাক্তার|ডা|doctor|dr)(?:\s*[.:ঃ]+\s*|\s+)/i; // bn-ok

/** এই পেশা কি "ডা." লিখতে পারেন? শুধু এলোপ্যাথি ডাক্তার (পদ্ধতি জানা না থাকলে এলোপ্যাথি) */
export const titleEligible = (type: string | null | undefined, system: unknown) =>
  type === 'doctor' && (system == null || system === '' || system === 'allopathy');

/** পদবি বাদে নাম ("ডা. ডা. করিম" → "করিম"); শুধু পদবি হলে যেমন ছিল */
export function stripTitle(name: string): string {
  let s = name;
  for (let i = 0; i < 4; i++) {
    const m = TITLE_RE.exec(s);
    if (!m) break;
    s = s.slice(m[0].length);
  }
  const out = s.trim();
  return out ? out : name.trim();
}

export const hasTitle = (name: string | null | undefined) => !!name && TITLE_RE.test(name) && stripTitle(name) !== name.trim();

/**
 * নামের ঘরের ভুল (সার্ভারের মতো): সুইচ চালু, পেশা "ডা." লিখতে পারেন না, আর নামের শুরুতে পদবি — তখন বার্তা; নইলে null।
 * type null = পেশাজীবী নন (সাধারণ ব্যবহারকারী) — কিছু নয়।
 */
export function titleError(name: string | null | undefined, type: string | null | undefined, system: unknown): string | null {
  if (!type || !titleGuardOn() || titleEligible(type, system) || !hasTitle(name)) return null;
  return TG.error();
}

/** নামের ঘরের নিচের ছোট লেখা — সুইচ চালু আর পেশা "ডা." লিখতে পারেন না */
export const titleHint = (type: string | null | undefined, system: unknown) =>
  !!type && titleGuardOn() && !titleEligible(type, system) ? TG.hint() : null;

// ---------- পেশার নাম ----------
interface Labelled { type?: unknown; system?: unknown; typeLabel?: unknown; typeLabelEn?: unknown }

/**
 * অ-এলোপ্যাথিক চিকিৎসকের পেশা: সার্ভারের typeLabel (সবাই এক কথা বলে) — না থাকলে সুইচ চালু হলে নিজের হিসাব; নইলে null
 * (ডাকা জায়গা নিজের পুরোনো নাম দেখায়, যেমন badgeLabel → "ডাক্তার")।
 */
export function practitionerLabel(x: Labelled | null | undefined): string | null {
  if (!x || x.type !== 'doctor') return null;
  const bn = typeof x.typeLabel === 'string' ? x.typeLabel : '';
  if (bn && bn !== 'ডাক্তার') return tr(typeof x.typeLabelEn === 'string' && x.typeLabelEn ? x.typeLabelEn : bn, bn);
  if (!titleGuardOn() || typeof x.system !== 'string') return null;
  return practitionerName(x.system);
}
