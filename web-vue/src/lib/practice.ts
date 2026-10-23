// চিকিৎসা পদ্ধতি (এলোপ্যাথি/হোমিওপ্যাথি/ইউনানি/আয়ুর্বেদ) আর নতুন পেশাজীবী (ফিজিওথেরাপিস্ট, সাইকোলজিস্ট) —
// scratchpad practice CONTRACT / backend/docs/PRACTICE.md। অ্যাপের একই নিয়ম।
// সব সুইচের পেছনে (lib/features.ts): practice_systems, pro_physio, pro_psychologist — বন্ধ থাকলে সাইট আগের মতোই:
// পদ্ধতির লেবেল/বাছাই/ছাঁকনি নেই, সবাই এলোপ্যাথি; নতুন ধরনের ট্যাব/ব্যাজ নেই।
// নতুন লেখা React ওয়েবে ছিল না — তাই প্রতিটা বাংলা লাইনে bn-ok (scripts/check-bangla.py)।
import { featureOn } from './features';
import { tr } from './i18n';

export type MedSystem = 'allopathy' | 'homeopathy' | 'unani' | 'ayurveda';
export const SYSTEMS: MedSystem[] = ['allopathy', 'homeopathy', 'unani', 'ayurveda'];
/** নতুন পেশাজীবী — চেম্বার, অ্যাপয়েন্টমেন্ট, অনলাইন চেম্বার, ফি, রসিদ আগের মতোই */
export const NEW_TYPES = ['physio', 'psychologist'];

// ---------- সুইচ ----------
export const systemsOn = () => featureOn('practice_systems');
export const physioOn = () => featureOn('pro_physio');
export const psychOn = () => featureOn('pro_psychologist');
/** পুরোনো ধরন সবসময়; নতুন ধরন শুধু নিজের সুইচ চালু থাকলে */
export const typeOn = (t: string) => (t === 'physio' ? physioOn() : t === 'psychologist' ? psychOn() : true);
export const newTypesOn = () => NEW_TYPES.filter(typeOn);
export const isNewType = (t: string | null | undefined) => !!t && NEW_TYPES.includes(t);

// ---------- পদ্ধতি ----------
export const isSystem = (v: unknown): v is MedSystem => typeof v === 'string' && (SYSTEMS as string[]).includes(v);
/** ডাক্তারের পদ্ধতি — সুইচ বন্ধ বা জানা না থাকলে এলোপ্যাথি (চুক্তি §৩: বন্ধ থাকলে সবাই এলোপ্যাথি) */
export const systemOf = (system: unknown): MedSystem => (systemsOn() && isSystem(system) ? system : 'allopathy');
/** নামের পাশে পদ্ধতির লেবেল — শুধু ডাক্তার (ধরন জানা না থাকলে ডাক্তার ধরা), সুইচ চালু থাকলে; নইলে null */
export const systemTag = (type: string | null | undefined, system: unknown): string | null =>
  systemsOn() && (!type || type === 'doctor') ? systemLabel(systemOf(system)) : null;

export function systemLabel(s: string): string {
  switch (s) {
    case 'homeopathy': return tr('Homeopathy', 'হোমিওপ্যাথি'); // bn-ok
    case 'unani': return tr('Unani', 'ইউনানি'); // bn-ok
    case 'ayurveda': return tr('Ayurveda', 'আয়ুর্বেদ'); // bn-ok
    default: return tr('Allopathy', 'এলোপ্যাথি'); // bn-ok
  }
}
/** নিবন্ধন দেয় যে প্রতিষ্ঠান */
export function regBody(s: string): string {
  switch (s) {
    case 'homeopathy': return tr('Bangladesh Homeopathic Board', 'বাংলাদেশ হোমিওপ্যাথিক বোর্ড'); // bn-ok
    case 'unani':
    case 'ayurveda': return tr('Bangladesh Unani & Ayurvedic Board', 'বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড'); // bn-ok
    default: return 'BMDC';
  }
}
/** ডিগ্রির উদাহরণ */
export function degreeHint(s: string): string {
  switch (s) {
    case 'homeopathy': return 'DHMS / BHMS';
    case 'unani': return 'DUMS / BUMS';
    case 'ayurveda': return 'DAMS / BAMS';
    default: return 'MBBS / BDS';
  }
}
/** নিবন্ধন নম্বরের ঘরে উদাহরণ */
export const regPlaceholder = (s: string) => (s === 'allopathy' ? 'BMDC A-12345' : '');

// ---------- নতুন ধরনের নাম ----------
/** পুরো নাম (ব্যাজ, প্রোফাইল); নতুন ধরন না হলে null — ডাকা জায়গা নিজের পুরোনো নাম দেখায় */
export function newTypeLabel(t: string): string | null {
  if (t === 'physio') return tr('Physiotherapist', 'ফিজিওথেরাপিস্ট'); // bn-ok
  if (t === 'psychologist') return tr('Clinical psychologist / counsellor', 'ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর'); // bn-ok
  return null;
}
/** ছোট নাম (ট্যাব) */
export function newTypeShort(t: string): string | null {
  if (t === 'psychologist') return tr('Psychologist', 'সাইকোলজিস্ট'); // bn-ok
  return newTypeLabel(t);
}
export function newTypeIcon(t: string): string | null {
  if (t === 'physio') return 'directions_run';
  if (t === 'psychologist') return 'forum_outline';
  return null;
}
export function newTypeEmpty(t: string): string | null {
  if (t === 'physio') return tr('No physiotherapists found', 'কোনো ফিজিওথেরাপিস্ট পাওয়া যায়নি'); // bn-ok
  if (t === 'psychologist') return tr('No psychologists or counsellors found', 'কোনো সাইকোলজিস্ট বা কাউন্সেলর পাওয়া যায়নি'); // bn-ok
  return null;
}

// ---------- কোন সেবা ----------
/** ফিজিও: চেম্বার, অনলাইন, বাসায়; সাইকোলজিস্ট: চেম্বার, অনলাইন — বাসায় নয় */
export const newTypeHome = (t: string) => t === 'physio';
export const newTypeOnline = (t: string) => isNewType(t);

/** ফিজিওথেরাপিস্টের বাসায় সেবা — সার্ভারের catalog-defaults.json homeService.physio */
export const PHYSIO_HOME = ['physio_home_exercise', 'physio_home_stroke', 'physio_home_post_op', 'physio_home_elderly', 'physio_home_pain'];
export const PRACTICE_HOME_LABEL: Record<string, [string, string]> = {
  physio_home_exercise: ['Exercise therapy at home', 'বাসায় ব্যায়াম থেরাপি'], // bn-ok
  physio_home_stroke: ['Stroke / paralysis', 'স্ট্রোক বা প্যারালাইসিস'], // bn-ok
  physio_home_post_op: ['After surgery', 'অপারেশনের পরে'], // bn-ok
  physio_home_elderly: ['Elderly mobility', 'বয়স্কদের চলাফেরা'], // bn-ok
  physio_home_pain: ['Pain relief', 'ব্যথা কমানো'], // bn-ok
};

// ---------- বিশেষজ্ঞতা (সার্ভারের catalog-defaults.json: specialty.physio / psychologist / doctor_<পদ্ধতি>) ----------
export const PHYSIO_SPEC = ['physio_back_neck', 'physio_sports', 'physio_stroke', 'physio_pediatric', 'physio_post_surgery'];
export const PSYCH_SPEC = ['psy_depression_anxiety', 'psy_child', 'psy_couple_family', 'psy_addiction', 'psy_trauma'];
/** হোমিওপ্যাথি/ইউনানি/আয়ুর্বেদ: ছোট সাধারণ তালিকা + "সাধারণ" */
export const ALT_SPEC = ['alt_general', 'alt_skin_hair', 'alt_women', 'alt_child', 'alt_joint_pain', 'alt_digestive', 'alt_allergy'];
export const PRACTICE_SPEC_LABEL: Record<string, [string, string]> = {
  alt_general: ['General', 'সাধারণ'], // bn-ok
  alt_skin_hair: ['Skin & hair', 'চর্ম ও চুল'], // bn-ok
  alt_women: ["Women's health", 'নারী স্বাস্থ্য'], // bn-ok
  alt_child: ['Child health', 'শিশু স্বাস্থ্য'], // bn-ok
  alt_joint_pain: ['Joint & back pain', 'জয়েন্ট ও কোমর ব্যথা'], // bn-ok
  alt_digestive: ['Digestive problems', 'হজমের সমস্যা'], // bn-ok
  alt_allergy: ['Allergy & breathing', 'অ্যালার্জি ও শ্বাসকষ্ট'], // bn-ok
  physio_back_neck: ['Back & neck pain', 'কোমর ও ঘাড় ব্যথা'], // bn-ok
  physio_sports: ['Sports injury', 'খেলাধুলার আঘাত'], // bn-ok
  physio_stroke: ['Stroke rehabilitation', 'স্ট্রোক পরবর্তী পুনর্বাসন'], // bn-ok
  physio_pediatric: ['Pediatric physiotherapy', 'শিশুদের ফিজিওথেরাপি'], // bn-ok
  physio_post_surgery: ['After surgery', 'অপারেশন পরবর্তী'], // bn-ok
  psy_depression_anxiety: ['Depression & anxiety', 'বিষণ্ণতা ও উদ্বেগ'], // bn-ok
  psy_child: ['Child & adolescent', 'শিশু ও কিশোর'], // bn-ok
  psy_couple_family: ['Couple & family', 'দাম্পত্য ও পারিবারিক'], // bn-ok
  psy_addiction: ['Addiction counselling', 'মাদকাসক্তি কাউন্সেলিং'], // bn-ok
  psy_trauma: ['Trauma', 'ট্রমা'], // bn-ok
};
/** catalog_get-এর তালিকার চাবি: নতুন ধরন নিজের নামে, অ-এলোপ্যাথি ডাক্তার "doctor_<পদ্ধতি>" */
export function specialtyCatalogKey(type: string, system: unknown): string | null {
  if (isNewType(type)) return type;
  if (type === 'doctor' && systemOf(system) !== 'allopathy') return 'doctor_' + systemOf(system);
  return null;
}
/** অ্যাপের ভেতরের তালিকা (catalog না পেলে); পুরোনো ধরনে null */
export function practiceSpecialties(type: string, system: unknown): string[] | null {
  if (type === 'physio') return PHYSIO_SPEC;
  if (type === 'psychologist') return PSYCH_SPEC;
  if (type === 'doctor' && systemOf(system) !== 'allopathy') return ALT_SPEC;
  return null;
}

// ---------- ব্যাজের কাগজ ----------
export interface PracticeDoc { type: string; label: string; hint: string; required: boolean }
/** নতুন ধরন বা অ-এলোপ্যাথি ডাক্তারের কাগজ; অন্যদের null (আগের নিয়ম) */
export function practiceDocs(type: string, system: unknown): PracticeDoc[] | null {
  // সার্ভারের VerifyDocs.kt (badge) — একই ধরন আর লেখা
  const idcard = { type: 'idcard', label: tr('Workplace ID card', 'কর্মস্থলের পরিচয়পত্র'), hint: tr('If you have it', 'থাকলে দিন'), required: false };
  const readable = () => tr('Name and institution readable', 'নাম আর প্রতিষ্ঠান পড়া যায়');
  if (type === 'physio') {
    return [
      { type: 'cert', label: tr('Degree certificate (BPT / MPT)', 'ডিগ্রির সনদ (BPT / MPT)'), hint: readable(), required: true }, // bn-ok
      { type: 'member', label: tr('Professional body membership', 'পেশাজীবী সংগঠনের সদস্যপত্র'), hint: tr('If you have it', 'থাকলে দিন'), required: false }, // bn-ok
      idcard,
    ];
  }
  if (type === 'psychologist') {
    return [
      { type: 'cert', label: tr('Degree certificate (MS / MPhil in clinical or counselling psychology)', 'ডিগ্রির সনদ (ক্লিনিক্যাল বা কাউন্সেলিং সাইকোলজিতে MS / MPhil)'), hint: readable(), required: true }, // bn-ok
      { type: 'member', label: tr('Bangladesh Clinical Psychology Society membership', 'বাংলাদেশ ক্লিনিক্যাল সাইকোলজি সোসাইটির সদস্যপত্র'), hint: tr('If you have it', 'থাকলে দিন'), required: false }, // bn-ok
      idcard,
    ];
  }
  if (type === 'doctor' && systemOf(system) !== 'allopathy') {
    const body = regBody(systemOf(system));
    return [
      { type: 'cert', label: tr(`${body} registration certificate`, `${body}-এর নিবন্ধন সনদ`), hint: tr('Number and name readable', 'নম্বর আর নাম পড়া যায়'), required: true }, // bn-ok
      idcard,
    ];
  }
  return null;
}

// ---------- প্রেসক্রিপশন ----------
// কী লেখা যায়:
//   full   — এলোপ্যাথি ডাক্তার: আগের মতো (ওষুধের তালিকা থেকে খোঁজা, ফার্মেসিতে পাঠানো)
//   free   — হোমিওপ্যাথি/ইউনানি/আয়ুর্বেদ: ওষুধ নিজে লেখা (তালিকা/মেলানো নেই), ফার্মেসিতে পাঠানো নেই; পরীক্ষা চলবে
//   advice — ফিজিওথেরাপিস্ট: শুধু "পরামর্শ ও ব্যায়াম" — ওষুধ নেই
//   notes  — সাইকোলজিস্ট: সেশন নোট/পরামর্শ — ওষুধ নেই (ওষুধের জন্য সাইকিয়াট্রিস্ট)
export type RxKind = 'full' | 'free' | 'advice' | 'notes';
export function rxKind(type: string | null | undefined, system: unknown): RxKind {
  if (type === 'physio') return 'advice';
  if (type === 'psychologist') return 'notes';
  return systemOf(system) === 'allopathy' ? 'full' : 'free';
}
/** সার্ভারের rxRules (rx_me আর প্রতিটা প্রেসক্রিপশনে; Practice.rxRules) — থাকলে এটাই মানা, সুইচ বন্ধ থাকলেও */
export interface RxRules { medicines?: boolean; freeText?: boolean; pharmacy?: boolean; tests?: boolean; section?: string | null; notice?: string | null }
export function rxKindOf(type: string | null | undefined, system: unknown, rules?: RxRules | null): RxKind {
  if (!rules || typeof rules.medicines !== 'boolean') return rxKind(type, system);
  if (!rules.medicines) return type === 'psychologist' || !!rules.notice ? 'notes' : 'advice';
  return rules.freeText ? 'free' : 'full';
}
/** ফার্মেসিতে পাঠানো যায় শুধু এলোপ্যাথি প্রেসক্রিপশন */
export const rxPharmacyOk = (type: string | null | undefined, system: unknown, rules?: RxRules | null) =>
  rules && typeof rules.pharmacy === 'boolean' ? rules.pharmacy : rxKind(type, system) === 'full';
/** প্রেসক্রিপশন লিখতে পারেন: ডাক্তার; নতুন ধরন সুইচ চালু থাকলে */
export const writesRx = (type: string | null | undefined) => type === 'doctor' || (isNewType(type) && typeOn(type!));

export const T = {
  system: () => tr('Medical system', 'চিকিৎসা পদ্ধতি'), // bn-ok
  all: () => tr('All', 'সব'),
  regBody: () => tr('Registered with', 'নিবন্ধন'), // bn-ok
  degrees: () => tr('Degrees', 'ডিগ্রি'),
  bodyHint: (s: string) => tr(`Registration: ${regBody(s)} · Degree: ${degreeHint(s)}`, `নিবন্ধন: ${regBody(s)} · ডিগ্রি: ${degreeHint(s)}`), // bn-ok
  regNoFor: (s: string) => tr(`${regBody(s)} registration no.`, `${regBody(s)} নিবন্ধন নম্বর`), // bn-ok
  reverify: () => tr('Changing the medical system sends your badge back for verification.', 'চিকিৎসা পদ্ধতি বদলালে ব্যাজ আবার যাচাইয়ে যাবে।'), // bn-ok
  systemFixed: () => tr('To change it, correct your badge — an admin checks it again.', 'বদলাতে ব্যাজের তথ্য সংশোধন করুন — অ্যাডমিন আবার যাচাই করবেন।'), // bn-ok
  physioDocsHint: () => tr('Degree BPT / MPT; add your professional body membership if you have one.', 'ডিগ্রি BPT / MPT; পেশাজীবী সংগঠনের সদস্য হলে সেটাও দিন।'), // bn-ok
  psychDocsHint: () => tr('MS / MPhil in clinical or counselling psychology; add your Bangladesh Clinical Psychology Society membership if you have one.', 'ক্লিনিক্যাল বা কাউন্সেলিং সাইকোলজিতে MS / MPhil; বাংলাদেশ ক্লিনিক্যাল সাইকোলজি সোসাইটির সদস্য হলে সেটাও দিন।'), // bn-ok
  // প্রেসক্রিপশন
  freeMeds: () => tr('Write the medicines yourself — no medicine list, matching or pharmacy sending.', 'ওষুধ নিজে লিখুন — ওষুধের তালিকা, মেলানো বা ফার্মেসিতে পাঠানো নেই।'), // bn-ok
  adviceExercise: () => tr('Advice & exercises', 'পরামর্শ ও ব্যায়াম'), // bn-ok
  sessionNotes: () => tr('Session notes & advice', 'সেশন নোট ও পরামর্শ'), // bn-ok
  assessment: () => tr('Assessment', 'মূল্যায়ন'), // bn-ok
  noMedsPhysio: () => tr('Physiotherapists do not prescribe medicines here.', 'ফিজিওথেরাপিস্ট এখানে ওষুধ লেখেন না।'), // bn-ok
  psychiatrist: () => tr('If medicine is needed, see a psychiatrist (allopathic doctor)', 'ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান'), // bn-ok
  needAdvice: () => tr('Write the advice', 'পরামর্শ লিখুন'), // bn-ok
  noPharmacy: () => tr('This prescription is not sent to pharmacies — buy the medicines from a shop of this system.', 'এই প্রেসক্রিপশন ফার্মেসিতে পাঠানো যায় না — এই পদ্ধতির দোকান থেকে ওষুধ নিন।'), // bn-ok
  physioHome: () => tr('Physio at home', 'বাসায় ফিজিওথেরাপিস্ট'), // bn-ok
  physioHomeEmpty: () => tr('No physiotherapist who visits homes here yet', 'এখানে বাসায় যান এমন ফিজিওথেরাপিস্ট এখনো নেই'), // bn-ok
  // সাইকোলজিস্টের গোপনীয়তা (care_share_psych — কেয়ারগিভারপ্রতি, শুরুতে বন্ধ)
  shareTitle: () => tr('Psychologist appointments and notes', 'সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট ও নোট'), // bn-ok
  shareSub: () => tr('Off: this caregiver cannot see them, and gets no notifications about them.', 'বন্ধ থাকলে এই কেয়ারগিভার এগুলো দেখতে পান না, নোটিফিকেশনও পান না।'), // bn-ok
  privateNote: () => tr('Caregivers do not see psychologist appointments and notes unless you allow it.', 'আপনি অনুমতি না দিলে কেয়ারগিভার সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট আর নোট দেখেন না।'), // bn-ok
  privateLink: () => tr('Caregiver settings', 'কেয়ারগিভারের সেটিং'), // bn-ok
  sharedByPatient: () => tr('The patient allowed you to see this', 'রোগী আপনাকে এটা দেখার অনুমতি দিয়েছেন'), // bn-ok
};
