import { computed, watch } from 'vue';
import { tr } from '@/lib/i18n';
import { useSession, workHas } from '@/lib/session';
import { store } from '@/lib/store';
import { writesRx } from '@/lib/practice';
import { TT } from '@/sections/work/today/text';
import { useMyPayBiz } from '@/sections/pay/biz/bizlist';
import { T as PT } from '@/sections/pay/text';

export interface NavItem { to: string; label: string; icon: string; iconOn: string; match: (p: string) => boolean }

/** শেষবার এই ব্রাউজারে যিনি ছিলেন তাঁর কাজ ছিল কি না — কাজের তথ্য আসার আগেই ট্যাবের নাম ঠিক রাখতে */
const HAS_KEY = 'lf.work.has';

export function useNavItems() {
  const s = useSession();
  // "আমার কাজ" ট্যাব সবসময় থাকে (পরে এসে মেনু সরে যায় না): কাজ থাকলে "আমার কাজ", না থাকলে (বা অতিথি) "সেবা দিন"
  watch(
    () => [s.ready, s.guest, s.workLoaded, workHas(s.work)] as const,
    ([ready, guest, loaded, has]) => {
      if (ready && !guest && loaded) store.set(HAS_KEY, has ? '1' : '0');
    },
    { immediate: true },
  );
  const hasWork = computed(() => {
    if (s.ready && s.guest) return false;
    if (s.ready && s.workLoaded) return workHas(s.work);
    if (workHas(s.work)) return true;
    return store.get(HAS_KEY) !== '0';
  });
  return computed<NavItem[]>(() => [
    { to: '/', label: tr('Home', 'হোম'), icon: 'nav_home', iconOn: 'nav_home_fill', match: (p) => p === '/' || p.startsWith('/request') || p.startsWith('/my-requests') },
    { to: '/appointment', label: tr('Appointment', 'অ্যাপয়েন্টমেন্ট'), icon: 'nav_event', iconOn: 'nav_event_fill', match: (p) => p.startsWith('/appointment') || p.startsWith('/my-appointments') },
    { to: '/health', label: tr('Health', 'স্বাস্থ্য'), icon: 'nav_health', iconOn: 'nav_health_fill', match: (p) => p.startsWith('/health') || p.startsWith('/invoices') },
    { to: '/services', label: tr('Services', 'সেবা'), icon: 'nav_services', iconOn: 'nav_services_fill', match: (p) => p.startsWith('/services') || p.startsWith('/prescriptions') },
    { to: '/work', label: hasWork.value ? TT.myWork() : TT.serveTab(), icon: 'nav_work', iconOn: 'nav_work_fill', match: (p) => p.startsWith('/work') },
  ]);
}

export interface WorkNavItem { to: string; label: string; icon: string; match: (p: string) => boolean; child?: boolean }

/** বড় পর্দার বাঁ মেনুতে "আমার কাজ"-এর নিচে ভূমিকা অনুযায়ী ভাগ (দুই স্তরের বেশি নয়) */
export function useWorkNav() {
  const s = useSession();
  const pay = useMyPayBiz();
  return computed<WorkNavItem[]>(() => {
    const w = s.work;
    if (s.guest || !workHas(w)) return [];
    const under = (base: string) => (p: string) => p === base || p.startsWith(base + '/');
    const out: WorkNavItem[] = [{ to: '/work', label: TT.title(), icon: 'today', match: (p) => p === '/work' || p === '/work/' }];
    if (w.professional) out.push({ to: '/work/pro', label: w.professional === 'nurse' ? TT.homecare() : TT.chamber(), icon: w.professional === 'nurse' ? 'home_health' : 'stethoscope', match: under('/work/pro') });
    if (writesRx(w.professional) || w.assisting.length) out.push({ to: '/work/rx', label: tr('Prescriptions', 'প্রেসক্রিপশন'), icon: 'edit_note', match: under('/work/rx') });
    if (w.pharmacy) {
      out.push({ to: '/work/pharmacy', label: TT.pharmacy(), icon: 'storefront', match: (p) => under('/work/pharmacy')(p) && !p.startsWith('/work/pharmacy/shop/sales') });
      if ((w.pharmacy as { status?: string }).status === 'approved') out.push({ to: '/work/pharmacy/shop/sales', label: TT.sell(), icon: 'payments', match: under('/work/pharmacy/shop/sales'), child: true });
    }
    if (w.lab) out.push({ to: '/work/lab', label: TT.lab(), icon: 'local_hospital', match: under('/work/lab') });
    if (w.bloodBanks.length) out.push({ to: '/work/bloodbank', label: TT.bloodbank(), icon: 'bloodtype', match: under('/work/bloodbank') });
    if (w.ambulances.length) out.push({ to: '/work/ambulance', label: TT.ambulance(), icon: 'ambulance', match: under('/work/ambulance') });
    if (pay.value.length) out.push({ to: '/work/payments', label: PT.bizTitle(), icon: 'payments', match: under('/work/payments') });
    return out;
  });
}
