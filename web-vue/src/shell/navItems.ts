import { computed } from 'vue';
import { tr } from '@/lib/i18n';
import { useSession, workHas } from '@/lib/session';

export interface NavItem { to: string; label: string; icon: string; iconOn: string; match: (p: string) => boolean }

export function useNavItems() {
  const s = useSession();
  return computed<NavItem[]>(() => {
    const items: NavItem[] = [
      { to: '/', label: tr('Home', 'হোম'), icon: 'nav_home', iconOn: 'nav_home_fill', match: (p) => p === '/' || p.startsWith('/request') || p.startsWith('/my-requests') },
      { to: '/appointment', label: tr('Appointment', 'অ্যাপয়েন্টমেন্ট'), icon: 'nav_event', iconOn: 'nav_event_fill', match: (p) => p.startsWith('/appointment') || p.startsWith('/my-appointments') },
      { to: '/health', label: tr('Health', 'স্বাস্থ্য'), icon: 'nav_health', iconOn: 'nav_health_fill', match: (p) => p.startsWith('/health') || p.startsWith('/prescriptions') },
      { to: '/services', label: tr('Services', 'সেবা'), icon: 'nav_services', iconOn: 'nav_services_fill', match: (p) => p.startsWith('/services') },
    ];
    // "আমার কাজ" শুধু যাঁর নিজের ব্যবসা, চেম্বার বা বাসায় সেবা আছে
    if (!s.guest && workHas(s.work)) items.push({ to: '/work', label: tr('My work', 'আমার কাজ'), icon: 'nav_work', iconOn: 'nav_work_fill', match: (p) => p.startsWith('/work') });
    return items;
  });
}
