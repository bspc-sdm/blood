// কাঠামো: বাম মেনু + উপরের সারি + পাতা
import { defineComponent, h as vh } from 'vue';
import { h, icon } from './h';
import { S, logout, parseHash, pendingCount, refreshAll, route, top } from './state';
import { bn } from './util';
import { PAGES, Dashboard } from './pages';
import { apptReportsOn, payReportsOn } from './pages/ApptReports';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  count?: () => number;
  owner?: boolean;
  /** ফিচার সুইচ চালু থাকলেই মেনুতে */
  show?: () => boolean;
}
const NAV: { group: string | null; items: NavItem[] }[] = [
  { group: null, items: [
    { path: '', label: 'ড্যাশবোর্ড', icon: 'home' },
    { path: 'analytics', label: 'অ্যানালিটিক্স', icon: 'chart' },
    { path: 'emergency', label: 'জরুরি আহ্বান', icon: 'warning' },
  ] },
  { group: 'অনুমোদন', items: [
    { path: 'badges', label: 'ব্যাজ', icon: 'verified', count: () => pendingCount('badges') },
    { path: 'pharmacies', label: 'ফার্মেসি', icon: 'store', count: () => pendingCount('pharmacies') },
    { path: 'ambulances', label: 'অ্যাম্বুলেন্স', icon: 'ambulance', count: () => pendingCount('ambulances') },
    { path: 'bloodBanks', label: 'ব্লাড ব্যাংক', icon: 'blood', count: () => pendingCount('bloodBanks') },
    { path: 'labs', label: 'প্যাথলজি', icon: 'hospital', count: () => pendingCount('labs') },
    { path: 'hospitals', label: 'হাসপাতাল', icon: 'hospital', count: () => pendingCount('hospitalRequests') },
  ] },
  { group: 'সহায়তা', items: [
    { path: 'tickets', label: 'আপিল ও সমস্যা', icon: 'help', count: () => pendingCount('tickets') },
    { path: 'reports', label: 'রিপোর্ট', icon: 'flag', count: () => pendingCount('reports') },
    { path: 'apptreports', label: 'অনলাইন, কল ও মেসেজের রিপোর্ট', icon: 'flag', show: () => apptReportsOn() || payReportsOn() }, // bn-ok
    { path: 'corrections', label: 'তারিখ সংশোধন', icon: 'calendar', count: () => pendingCount('corrections') },
  ] },
  { group: 'ব্যবস্থাপনা', items: [
    { path: 'users', label: 'ব্যবহারকারী', icon: 'person' },
    { path: 'catalog', label: 'বাছাইয়ের তালিকা', icon: 'menu' },
    { path: 'labtests', label: 'রিপোর্টের ছক', icon: 'hospital' },
    { path: 'features', label: 'ফিচার সুইচ', icon: 'check' }, // bn-ok
    { path: 'titlenames', label: '‘ডা.’ লেখা নাম', icon: 'person' }, // bn-ok
    { path: 'log', label: 'কাজের ইতিহাস', icon: 'history' },
    { path: 'admins', label: 'অ্যাডমিন', icon: 'admin', owner: true },
    { path: 'demo', label: 'ডেমো (পরীক্ষা)', icon: 'person', owner: true }, // ডেমো — মুছলে এই লাইন, PAGES.demo আর pages/Demo.ts মুছবেন
  ] },
];

function nav() {
  const r = parseHash();
  const cur = r.name;
  const kids = [];
  for (const g of NAV) {
    if (g.group) kids.push(h('div', { class: 'nav-group', text: g.group }));
    for (const it of g.items) {
      if (it.owner && !(S.me && S.me.isOwner)) continue;
      if (it.show && !it.show()) continue;
      const n = it.count ? it.count() : 0;
      const on = cur === it.path || (cur === 'user' && it.path === 'users') || (cur === 'ticket' && it.path === 'tickets') || ((cur === 'callreports' || cur === 'msgreports' || cur === 'payreports') && it.path === 'apptreports')
        || (cur === 'verify' && r.arg.startsWith(`${it.path}:`));
      kids.push(h('a', { href: `#/${it.path}`, class: on ? 'on' : null, 'aria-current': on ? 'page' : null, onclick: (ev: MouseEvent) => {
        S.navOpen = false;
        // এখনকার পাতার মেনুতেই চাপলে ঠিকানা বদলায় না — তবু নতুন করে আনা
        if (location.hash === `#/${it.path}`) {
          ev.preventDefault();
          route(false, true);
        }
      } },
      icon(it.icon), h('span', { class: 'lbl', text: it.label }), n ? h('span', { class: 'n', text: bn(n) }) : null));
    }
  }
  return h('nav', { class: 'nav', 'aria-label': 'মেনু' }, kids);
}

export default defineComponent({
  name: 'AdminShell',
  setup() {
    return () => {
      const t = top.value;
      const r = parseHash();
      const Page = PAGES[r.name] || Dashboard;
      return h('div', { class: S.navOpen ? 'shell open' : 'shell' },
        h('aside', { class: 'side' },
          h('div', { class: 'logo' }, h('div', { class: 'dot' }, icon('blood')), h('b', { text: 'Lifolo' }), h('span', { text: 'অ্যাডমিন' })),
          nav(),
          h('div', { class: 'side-foot' },
            h('div', { class: 'who', text: (S.me && S.me.email) || '' }),
            h('button', { class: 'btn quiet', type: 'button', onclick: () => logout('লগআউট হয়েছে।') }, icon('logout'), 'লগআউট'))),
        h('div', { class: 'scrim', onclick: () => { S.navOpen = false; } }),
        h('div', { class: 'main' },
          h('header', { class: 'topbar' },
            h('button', { class: 'icon-btn menu-btn', type: 'button', 'aria-label': 'মেনু খুলুন', onclick: () => { S.navOpen = true; } }, icon('menu')),
            h('h1', null, t.title, t.sub ? h('span', { class: 'sub', text: t.sub }) : null),
            h('div', { class: 'top-actions' }, t.actions ? (t.actions() as never) : null),
            h('button', { class: 'icon-btn', type: 'button', title: 'রিফ্রেশ', 'aria-label': 'রিফ্রেশ', onclick: () => refreshAll() }, icon('refresh'))),
          h('main', { class: 'page', id: 'page', tabindex: '-1' }, vh(Page, { key: S.routeSeq, r, fresh: S.fresh, keepY: S.keepY }))));
    };
  },
});
