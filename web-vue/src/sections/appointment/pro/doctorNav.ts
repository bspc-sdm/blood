// ডাক্তারের (আর চেম্বারের অন্য পেশাদারের) এক সারি ট্যাব — "আমার চেম্বার" (/work/pro) আর প্রেসক্রিপশন (/work/rx) দুটো পাতাতেই একই
// (আমার কাজ CONTRACT §২: আগে দুই জায়গায় দুই "রোগী", দুই সারি)।
//   আজকের রোগী /work/pro · বুকিং /work/pro/bookings · রোগী /work/rx (প্রেসক্রিপশন না লিখলে /work/pro/patients) · প্রেসক্রিপশন /work/rx/list
// কম ব্যবহারের পাতা "আরও"-তে: নতুন প্রেসক্রিপশন, রিপোর্ট, সহকারী, সেটআপ, বাসায় সেবা।
import { computed, toValue, type MaybeRefOrGetter } from 'vue';
import { tr } from '@/lib/i18n';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { rxKindOf, writesRx } from '@/lib/practice';
import type { PillTab, MenuItem } from '@/ui/kit';
import { isHomeType, parseAppointment } from '../model';
import type { QueueChamber } from '../queue';

export type DocTab = 'today' | 'bookings' | 'patients' | 'rx' | 'more';
export const DOC_PATH: Record<Exclude<DocTab, 'more'>, (rx: boolean) => string> = {
  today: () => '/work/pro',
  bookings: () => '/work/pro/bookings',
  patients: (rx) => (rx ? '/work/rx' : '/work/pro/patients'),
  rx: () => '/work/rx/list',
};

export function useDoctorNav(type: MaybeRefOrGetter<string | null | undefined>) {
  const s = useSession();
  const nav = useNav();
  const rx = computed(() => writesRx(toValue(type)));
  const on = () => s.ready && !s.guest;
  // একই চাবি — পাতাগুলো আগেই আনে, আবার আনা নয়
  const appts = useCall<{ items: any[] }>('my_appointments', {}, () => ({ enabled: on() }));
  const q = useCall<{ chambers: QueueChamber[] }>('queue_today', {}, () => ({ enabled: on(), refetchInterval: 30_000, staleTime: 10_000 }));
  const me = useCall<{ system?: string | null; rxRules?: any }>('rx_me', {}, () => ({ enabled: on() && rx.value, staleTime: 5 * 60_000 }));
  const pending = computed(() => (appts.data?.items ?? []).map(parseAppointment).filter((a) => a.role === 'provider' && a.status === 'pending').length);
  const waiting = computed(() => (q.data?.chambers ?? []).reduce((n, c) => n + c.rows.filter((r) => r.state === 'waiting' || r.state === 'later').length, 0));
  const labs = computed(() => {
    const k = rxKindOf(toValue(type), me.data?.system ?? s.me?.badge?.system, me.data?.rxRules);
    return k === 'full' || k === 'free';
  });
  const tabs = computed<PillTab[]>(() => [
    { key: 'today', label: tr("Today's patients", 'আজকের রোগী'), icon: 'event_outline', selectedIcon: 'event_filled', count: waiting.value }, // bn-ok
    { key: 'bookings', label: tr('Bookings', 'বুকিং'), icon: 'calendar_month', count: pending.value },
    { key: 'patients', label: tr('Patients', 'রোগী'), icon: 'person_outline', selectedIcon: 'person_filled' },
    ...(rx.value ? [{ key: 'rx', label: tr('Prescriptions', 'প্রেসক্রিপশন'), icon: 'medication' }] : []),
  ]);
  const go = (k: string) => nav(DOC_PATH[k as Exclude<DocTab, 'more'>](rx.value));
  const more = computed<MenuItem[]>(() => [
    ...(rx.value ? [{ label: tr('New prescription', 'নতুন প্রেসক্রিপশন'), icon: 'edit_note', testid: 'more-write', onClick: () => nav('/work/rx/write') }] : []),
    ...(rx.value && labs.value ? [{ label: tr('Reports', 'রিপোর্ট'), icon: 'list_alt', testid: 'more-reviews', onClick: () => nav('/work/rx/reviews') }] : []),
    ...(rx.value ? [{ label: tr('Assistants', 'সহকারী'), icon: 'group_round', testid: 'more-team', onClick: () => nav('/work/rx/team') }] : []),
    { label: tr('Setup', 'সেটআপ'), icon: 'settings_outline', testid: 'more-setup', onClick: () => nav('/work/pro/chamber') },
    ...(isHomeType(toValue(type)) ? [{ label: tr('Home care', 'বাসায় সেবা'), icon: 'home_health', testid: 'more-home', onClick: () => nav('/work/pro/home') }] : []),
  ]);
  return { tabs, go, more, rx };
}
