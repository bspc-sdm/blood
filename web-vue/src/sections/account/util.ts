// অ্যাকাউন্ট অংশের ছোট সাহায্যকারী: ব্যাজের নাম, ছবি ছোট করা (অ্যাপের Photo.kt), নোটিফিকেশন → পাতা (অ্যাপের openFromData)
import { computed, type ComputedRef } from 'vue';
import { tr } from '@/lib/i18n';
import { TEST_MODE } from '@/lib/testmode';
import { useSession, workHas, type CommunityMe, type WorkSummary } from '@/lib/session';

// ---------- ব্যাজ (অ্যাপের Badges) ----------
export function badgeLabel(type: string): string {
  switch (type) {
    case 'doctor': return tr('Doctor', 'ডাক্তার');
    case 'nurse': return tr('Nurse', 'নার্স');
    case 'nutritionist': return tr('Nutritionist', 'পুষ্টিবিদ');
    case 'trainer': return tr('Fitness trainer', 'ফিটনেস ট্রেইনার');
    default: return tr('Health Expert', 'স্বাস্থ্য বিশেষজ্ঞ');
  }
}

export const BUSINESS_KINDS = ['ambulance', 'pharmacy', 'bloodbank', 'lab'] as const;
export const CHAMBER_TYPES = ['doctor', 'nutritionist', 'trainer'];
export const SUPPORT_EMAIL = 'ekapkbd@gmail.com';
export const PRIVACY_URL = 'https://lifolo.com/privacy';
export const TERMS_URL = 'https://lifolo.com/terms';

/** অ্যাপের MyWork.hasBusiness: চার রকম ব্যবসার কোনোটা (পেশাদার ব্যাজ বাদে) */
export const hasBusiness = (w: WorkSummary) => w.ambulances.length > 0 || !!w.pharmacy || w.bloodBanks.length > 0 || !!w.lab;

/**
 * সেশনের me আর ব্যবসার হিসাব। পরীক্ষার বিল্ডে window.__lfTest.acct দিয়ে বদলানো যায়
 * (নকল সার্ভারের উত্তর সব পাতায় এক, তাই "ব্যবসা আছে/নেই" দুটো ছবি তুলতে)।
 */
export function useAccountState(): ComputedRef<{ me: CommunityMe | null; work: WorkSummary; workLoaded: boolean }> {
  const s = useSession();
  return computed(() => {
    const o = TEST_MODE ? (window.__lfTest as { acct?: { me?: Partial<CommunityMe>; work?: Partial<WorkSummary> } } | undefined)?.acct : undefined;
    return {
      me: o?.me && s.me ? { ...s.me, ...o.me } : s.me,
      work: o?.work ? { ...s.work, ...o.work } : s.work,
      workLoaded: s.workLoaded || !!o?.work,
    };
  });
}

// ---------- ছবি (অ্যাপের Photo.kt) ----------
type Src = ImageBitmap | HTMLImageElement;

async function loadImage(file: Blob): Promise<Src> {
  try {
    // ছবির ঘোরানো (EXIF) ব্রাউজার নিজেই ঠিক করে
    return await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    const url = URL.createObjectURL(file);
    try {
      const img = new Image();
      img.src = url;
      await img.decode();
      return img;
    } catch {
      throw new Error(tr("Couldn't read the photo", 'ছবিটা পড়া যায়নি'));
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 0);
    }
  }
}
const dims = (s: Src) => (s instanceof HTMLImageElement ? [s.naturalWidth, s.naturalHeight] : [s.width, s.height]);

function canvas(w: number, h: number) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d');
  if (!ctx) throw new Error(tr("Couldn't read the photo", 'ছবিটা পড়া যায়নি'));
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, w, h);
  ctx.imageSmoothingQuality = 'high';
  return { c, ctx };
}
const jpeg = (c: HTMLCanvasElement, q: number) => c.toDataURL('image/jpeg', q).split(',')[1] ?? '';

/** প্রোফাইলের ছবি: মাঝখান থেকে বর্গ কেটে ২৫৬×২৫৬ JPEG (৮০%), আর তালিকার জন্য ৯৬×৯৬ (৭০%) */
export async function toAvatar(file: Blob): Promise<{ photo: string; thumb: string }> {
  const src = await loadImage(file);
  const [w, h] = dims(src);
  if (!w || !h) throw new Error(tr("Couldn't read the photo", 'ছবিটা পড়া যায়নি'));
  const side = Math.min(w, h);
  const big = canvas(256, 256);
  big.ctx.drawImage(src, (w - side) / 2, (h - side) / 2, side, side, 0, 0, 256, 256);
  const small = canvas(96, 96);
  small.ctx.drawImage(big.c, 0, 0, 256, 256, 0, 0, 96, 96);
  return { photo: jpeg(big.c, 0.8), thumb: jpeg(small.c, 0.7) };
}

/** কাগজের ছবি: লম্বা দিক সর্বোচ্চ ১৪০০, base64 ২৮০ KB-র মধ্যে না আসা পর্যন্ত মান কমানো */
export async function toDocument(file: Blob): Promise<string> {
  const src = await loadImage(file);
  const [w, h] = dims(src);
  let maxSide = 1400;
  let quality = 0.75;
  for (;;) {
    const scale = Math.min(1, maxSide / Math.max(w, h));
    const { c, ctx } = canvas(Math.round(w * scale), Math.round(h * scale));
    ctx.drawImage(src, 0, 0, c.width, c.height);
    const b64 = jpeg(c, quality);
    if (b64.length <= 280 * 1024 || maxSide <= 700) return b64;
    if (quality > 0.55) quality -= 0.1;
    else maxSide -= 200;
  }
}

// ---------- নোটিফিকেশন → পাতা (অ্যাপের MainActivity.openFromData) ----------
export function inboxTarget(data: Record<string, string>, me: CommunityMe | null, work: WorkSummary): string {
  const type = data.type;
  if (data.requestId) return type === 'response' ? `/my-requests/${data.requestId}` : `/request/${data.requestId}`;
  if (data.questionId) return `/health/question/${data.questionId}`;
  const approved = me?.badge?.status === 'approved' ? me.badge.type : null;
  switch (type) {
    // চেম্বারে আসা বুকিং → "আমার কাজ"-এর চেম্বার; নিজের নেওয়া → "আমার অ্যাপয়েন্টমেন্ট"
    case 'appointment':
      return data.role === 'provider' || (!data.role && approved && CHAMBER_TYPES.includes(approved)) ? '/work/pro' : '/my-appointments';
    case 'homevisit':
    case 'homecare':
      return '/appointment/home-care';
    case 'support':
      return data.ticketId ? `/help/${data.ticketId}` : '/help';
    // প্রেসক্রিপশনের খবর: সেই প্রেসক্রিপশন (পুরোনো নোটিফিকেশনে শুধু id — সেটাও প্রেসক্রিপশনের)
    case 'rx': {
      const rid = data.rxId || data.id;
      return rid ? `/prescriptions/${encodeURIComponent(rid)}` : '/prescriptions';
    }
    case 'rx_review':
      return '/work/pro';
    // কেয়ারগিভারকে রোগীর প্রেসক্রিপশনের খবর: কেয়ারগিভার পাতা রোগীর মোড চালু করে সেই প্রেসক্রিপশন খোলে
    case 'care':
      return data.asUid && data.rxId ? `/care?for=${encodeURIComponent(data.asUid)}&rx=${encodeURIComponent(data.rxId)}` : '/care';
    case 'badge':
      return '/health/badge';
    // ২.২৭.০: /work/pharmacy অনুমোদিত দোকানে খোলে "অর্ডার" ট্যাবের "নতুন"-এ; ওষুধ খোঁজার অনুরোধ "ওষুধ খোঁজা" ট্যাবে (২.৩০.০)
    case 'rx_pharmacy':
    case 'pharmacy_order':
    case 'pharmacy_mine':
      return '/work/pharmacy';
    case 'med_find':
      return '/work/pharmacy/finds';
    case 'rx_lab':
      return '/work/lab';
    case 'med_find_mine':
      return '/services/pharmacy/medicine';
    case 'services':
      if (data.kind && (BUSINESS_KINDS as readonly string[]).includes(data.kind)) return `/work/${data.kind}`;
      return workHas(work) ? '/work' : '/';
    default:
      return '/';
  }
}

/** নোটিফিকেশনের ধরন অনুযায়ী আইকন (অ্যাপের inboxIcon) */
export function inboxIcon(type?: string, kind?: string, title?: string): string {
  if (type === 'services') {
    const t = (title ?? '').toLowerCase();
    const k = kind || (t.includes('অ্যাম্বুলেন্স') || t.includes('ambulance') ? 'ambulance' : t.includes('ব্লাড ব্যাংক') || t.includes('blood bank') ? 'bloodbank' : t.includes('ফার্মেসি') || t.includes('pharmacy') ? 'pharmacy' : '');
    return k === 'ambulance' ? 'ambulance' : k === 'bloodbank' ? 'bloodtype' : k === 'pharmacy' ? 'medication' : 'storefront';
  }
  switch (type) {
    case 'appointment': return 'calendar_month';
    case 'homevisit':
    case 'homecare': return 'home_health';
    case 'answer': return 'forum_outline';
    case 'pharmacy_order':
    case 'pharmacy_mine':
    case 'med_find':
    case 'med_find_mine':
    case 'rx':
    case 'rx_pharmacy': return 'medication';
    case 'care': return 'health_and_safety_filled';
    case 'badge': return 'workspace_premium';
    case 'support': return 'help';
    case 'emergency': return 'warning';
    case 'eligible': return 'favorite';
    default: return 'water_drop';
  }
}

// ---------- সাহায্য ও আপিল (অ্যাপের Tickets) ----------
export const TICKET_CATEGORIES = ['account', 'badge', 'pharmacy', 'ambulance', 'bloodbank', 'homecare', 'appointment', 'blood', 'other'];
export function ticketLabel(c: string): string {
  switch (c) {
    case 'account': return tr('Account', 'অ্যাকাউন্ট');
    case 'badge': return tr('Badge (doctor/nurse)', 'ব্যাজ (ডাক্তার/নার্স)');
    case 'pharmacy': return tr('Pharmacy', 'ফার্মেসি');
    case 'ambulance': return tr('Ambulance', 'অ্যাম্বুলেন্স');
    case 'homecare': return tr('Home care', 'বাসায় সেবা');
    case 'bloodbank': return tr('Blood bank', 'ব্লাড ব্যাংক');
    case 'homevisit': return tr('Home nurse (old)', 'হোম নার্স (আগের)');
    case 'medorder': return tr('Medicine order', 'ওষুধের অর্ডার');
    case 'appointment': return tr('Appointment', 'অ্যাপয়েন্টমেন্ট');
    case 'blood': return tr('Blood request', 'রক্তের অনুরোধ');
    default: return tr('Something else', 'অন্য কিছু');
  }
}
export interface TicketMessage { from: 'user' | 'admin'; text: string; at: number }
export interface Ticket { id: string; category: string; status: string; messages: TicketMessage[]; createdAt: number; updatedAt: number }
export const ticketAnswered = (t: Ticket) => t.messages[t.messages.length - 1]?.from === 'admin';
export function ticketStatus(t: Ticket): string {
  if (t.status === 'closed') return tr('Closed', 'বন্ধ');
  return ticketAnswered(t) ? tr('Reply received', 'উত্তর এসেছে') : tr('Waiting for reply', 'উত্তরের অপেক্ষায়');
}

/** ?next= শুধু নিজের সাইটের পথ */
export function safeNext(raw: string | null): string | null {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return null;
  return raw;
}
