// পথ (route) — React ওয়েবের App.tsx-এর হুবহু একই ঠিকানা; প্রতিটা অংশ (section) পুরো পথ দেখে নিজের পাতা বাছে
import { createRouter, createWebHistory } from 'vue-router';
import NotFound from './pages/NotFound.vue';

const Blood = () => import('./sections/blood/BloodSection.vue');
const Services = () => import('./sections/services/ServicesSection.vue');
const Appointment = () => import('./sections/appointment/AppointmentSection.vue');
const Health = () => import('./sections/health/HealthSection.vue');
const Work = () => import('./sections/work/WorkSection.vue');
const Account = () => import('./sections/account/AccountSection.vue');

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
    // স্বাস্থ্য: আলোচনা, টিপস, প্রেসক্রিপশন
    { path: rest('/health'), component: Health },
    { path: rest('/prescriptions'), component: Health },
    // আমার কাজ (ব্যবসা/চেম্বার)
    { path: rest('/work'), component: Work },
    // অ্যাকাউন্ট: প্রোফাইল, সেটিংস, নোটিফিকেশন, সাহায্য, কেয়ারগিভার
    { path: rest('/profile'), component: Account },
    { path: rest('/settings'), component: Account },
    { path: rest('/inbox'), component: Account },
    { path: rest('/help'), component: Account },
    { path: rest('/care'), component: Account },
    { path: '/login', component: Account },
    { path: '/:pathMatch(.*)*', component: NotFound },
  ],
  // পাতা বদলালে উপরে যাওয়া AppShell নিজে করে (React-এর মতো)
});
