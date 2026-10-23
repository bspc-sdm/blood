// পথ (route) — React ওয়েবের App.tsx-এর হুবহু একই ঠিকানা; প্রতিটা অংশ (section) পুরো পথ দেখে নিজের পাতা বাছে
import { createRouter, createWebHistory } from 'vue-router';
import NotFound from './pages/NotFound.vue';
import { featureGuard } from './lib/features';

const Blood = () => import('./sections/blood/BloodSection.vue');
const Services = () => import('./sections/services/ServicesSection.vue');
const Appointment = () => import('./sections/appointment/AppointmentSection.vue');
const Health = () => import('./sections/health/HealthSection.vue');
const Work = () => import('./sections/work/WorkSection.vue');
const Account = () => import('./sections/account/AccountSection.vue');
const DeleteAccount = () => import('./pages/DeleteAccountPage.vue');
const DeactivateAccount = () => import('./pages/DeactivateAccountPage.vue');
const Invoices = () => import('./sections/invoices/InvoicesSection.vue');
const VerifyInvoice = () => import('./sections/invoices/VerifyPage.vue');

const rest = (p: string) => `${p}/:rest(.*)*`;

export const router = createRouter({
  history: createWebHistory('/'),
  linkActiveClass: 'rl-active',
  linkExactActiveClass: 'rl-exact',
  routes: [
    // হোম: রক্তের রিকোয়েস্ট
    { path: '/', component: Blood },
    { path: rest('/request'), component: Blood },
    { path: rest('/my-requests'), component: Blood },
    // সেবা: অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি
    { path: rest('/services'), component: Services },
    // অ্যাপয়েন্টমেন্ট: ডাক্তার, বাসায় সেবা, আমার অ্যাপয়েন্টমেন্ট
    { path: rest('/appointment'), component: Appointment },
    { path: rest('/my-appointments'), component: Appointment },
    // অনলাইন (ভিডিও) অ্যাপয়েন্টমেন্টের কলে ঢোকা (?role=provider&name=…) — calls/
    { path: '/appointments/:id/call', component: () => import('./calls/ApptCallPage.vue'), beforeEnter: featureGuard('appt_video') },
    // অ্যাপে কলের তালিকা
    { path: '/calls', component: () => import('./calls/CallHistoryPage.vue'), beforeEnter: featureGuard('calls_audio') },
    // অ্যাপে মেসেজ: কথোপকথনের তালিকা আর একটা কথোপকথন (একই পাতা — বড় পর্দায় পাশাপাশি) — chat/
    { path: '/messages/:threadId?', component: () => import('./chat/MessagesPage.vue'), beforeEnter: featureGuard('chat') },
    // স্বাস্থ্য: আলোচনা, টিপস
    { path: rest('/health'), component: Health },
    // আমার প্রেসক্রিপশন এখন সেবা → ফার্মেসিতে; পুরোনো ঠিকানা (নোটিফিকেশন, যাচাই লিংক, প্যাথলজির বাটন) সেখানে যায়
    {
      path: rest('/prescriptions'),
      redirect: (to) => {
        const parts = ([] as string[]).concat((to.params.rest as string[] | string | undefined) ?? []).filter(Boolean);
        return { path: ['/services/pharmacy/prescriptions', ...parts.map(encodeURIComponent)].join('/'), query: to.query, hash: to.hash };
      },
    },
    // আমার কাজ (ব্যবসা/চেম্বার)
    { path: rest('/work'), component: Work },
    // অ্যাকাউন্ট: প্রোফাইল, সেটিংস, নোটিফিকেশন, সাহায্য, কেয়ারগিভার
    { path: rest('/profile'), component: Account },
    { path: rest('/settings'), component: Account },
    { path: rest('/inbox'), component: Account },
    { path: rest('/help'), component: Account },
    { path: rest('/care'), component: Account },
    { path: '/login', component: Account },
    // অ্যাকাউন্ট মোছার পাবলিক পাতা (Google Play-র Delete account URL) — লগইন ছাড়াই খোলে
    { path: '/delete-account', component: DeleteAccount },
    // অ্যাকাউন্ট সাময়িক বন্ধ (মোছার বদলে)
    { path: '/deactivate-account', component: DeactivateAccount },
    // রসিদ: রোগীর তালিকা/খরচ, আর পাবলিক যাচাই (রসিদের QR — লগইন ছাড়া)
    { path: rest('/invoices'), component: Invoices },
    { path: '/v/:code?', component: VerifyInvoice },
    // bKash গেটওয়ের ফল — শুধু Lifolo-র নিজের আয় (pay_bkash_pgw); অবস্থা সার্ভার থেকে, ঠিকানা থেকে নয়
    { path: '/pay/result/:id?', component: () => import('./sections/pay/PgwResult.vue'), beforeEnter: featureGuard('pay_bkash_pgw') },
    { path: '/:pathMatch(.*)*', component: NotFound },
  ],
  // পাতা বদলালে উপরে যাওয়া AppShell নিজে করে (React-এর মতো)
});

// অন্য কেউ (ব্রাউজারের এক্সটেনশন, পরীক্ষার স্ক্রিপ্ট) history.pushState করলে history.state-এ Vue Router-এর নিজের
// চাবি (current, position …) থাকে না — তখন পরের router.push ঠিকানা হারায়, কার্ড চাপলে কিছু হয় না।
// React Router এমন অবস্থাতেও চলে; এখানে সেই এন্ট্রি ঠিক করে দেওয়া।
router.afterEach((to) => {
  const st = window.history.state as Record<string, unknown> | null;
  if (st && typeof st === 'object' && typeof st.current === 'string' && typeof st.position === 'number') return;
  window.history.replaceState({ ...(st || {}), back: null, current: to.fullPath, forward: null, position: Math.max(0, window.history.length - 1), replaced: true, scroll: null }, '');
});
