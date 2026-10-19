<script setup lang="ts">
// "আমার কাজ" (/work/*) — নিজের অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি আর পেশাদারের চেম্বার (অ্যাপের Work.kt)
import { computed, defineAsyncComponent } from 'vue';
import { useRoute } from 'vue-router';
import { Loading } from '@/ui/kit';
import Redirect from '@/shell/Redirect.vue';
import { matchPath, useLoc, userState } from '@/lib/nav';
import { useSession } from '@/lib/session';
import NotFound from '@/pages/NotFound.vue';
import WorkOverview from './WorkOverview.vue';
import AddBusiness from './AddBusiness.vue';
import MyAmbulances from './MyAmbulances.vue';
import AmbulanceForm from './AmbulanceForm.vue';
import MyPharmacy from './MyPharmacy.vue';
import PharmacyFinds from './PharmacyFinds.vue';
import PharmacyForm from './PharmacyForm.vue';
import MyBloodBanks from './MyBloodBanks.vue';
import BloodBankForm from './BloodBankForm.vue';
import MyLab from './MyLab.vue';
import LabForm from './LabForm.vue';

const lazy = (f: () => Promise<any>) => defineAsyncComponent({ loader: f, loadingComponent: Loading });
const Pro = lazy(() => import('../appointment/pro/ProfessionalWorkspace.vue'));
// প্রেসক্রিপশন (ডাক্তার ও সহকারী), ফার্মেসির অর্ডার/স্টক, প্যাথলজির অর্ডার/রিপোর্ট — আগে /doctor প্যানেলে ছিল
const DoctorRx = lazy(() => import('../rx/doctor/DoctorRx.vue'));
const PharmacyRx = lazy(() => import('../rx/partner/PharmacyRx.vue'));
const LabOrders = lazy(() => import('../rx/partner/LabOrders.vue'));

const route = useRoute();
const loc = useLoc();
const s = useSession();
const page = computed<{ k: string; id?: string | null }>(() => {
  const p = route.path;
  const m = (x: string) => matchPath({ path: x, end: true }, p);
  if (matchPath({ path: '/work/pro/*', end: false }, p)) return { k: 'pro' };
  if (matchPath({ path: '/work/rx/*', end: false }, p)) return { k: 'rx' };
  if (matchPath({ path: '/work/pharmacy/orders/*', end: false }, p)) return { k: 'pharmRx' };
  // ২.৩০.০ "দোকান"-এর ভেতরের বিক্রি/স্টক/এনালাইটিক্স (হিসাব-খাতা) — কাউন্টারের সাথে একই ভাগে
  if (matchPath({ path: '/work/pharmacy/shop/:sub', end: true }, p)) return { k: 'pharmRx' };
  if (matchPath({ path: '/work/lab/orders/*', end: false }, p)) return { k: 'labOrders' };
  if (m('/work')) return { k: 'overview' };
  if (m('/work/add')) return { k: 'add' };
  if (m('/work/ambulance')) return { k: 'amb' };
  if (m('/work/ambulance/new')) return { k: 'ambForm', id: null };
  const amb = m('/work/ambulance/:id');
  if (amb) return { k: 'ambForm', id: amb.params.id ?? null };
  // ২.২৭.০/২.৩০.০: অনুমোদিত হলে /work/pharmacy (পুরোনো লিংক, নোটিফিকেশন) খোলে "অর্ডার"-এ; "ওষুধ খোঁজা" /finds; দোকানের তথ্য /shop
  if (m('/work/pharmacy')) return { k: 'bizHome', id: 'pharmacy' };
  if (m('/work/pharmacy/finds')) return { k: 'finds' };
  if (m('/work/pharmacy/shop')) return { k: 'pharm' };
  if (m('/work/pharmacy/new') || m('/work/pharmacy/edit')) return { k: 'pharmForm' };
  if (m('/work/bloodbank')) return { k: 'bb' };
  if (m('/work/bloodbank/new')) return { k: 'bbForm', id: null };
  const bb = m('/work/bloodbank/:id');
  if (bb) return { k: 'bbForm', id: bb.params.id ?? null };
  if (m('/work/lab')) return { k: 'bizHome', id: 'lab' };
  if (m('/work/lab/info')) return { k: 'lab' };
  if (m('/work/lab/new') || m('/work/lab/edit')) return { k: 'labForm' };
  return { k: 'none' };
});
/** /work/pharmacy, /work/lab: অনুমোদিত হলে প্রথম ট্যাব (অর্ডার/অনুরোধ, "নতুন" ভাগ); নইলে (যোগ করা, যাচাই চলছে) নিজের পাতা */
const bizApproved = computed(() => {
  const kind = page.value.id;
  const biz = (kind === 'pharmacy' ? s.work.pharmacy : s.work.lab) as { status?: string } | null;
  return biz?.status === 'approved';
});
</script>

<template>
  <Pro v-if="page.k === 'pro'" />
  <DoctorRx v-else-if="page.k === 'rx'" />
  <PharmacyRx v-else-if="page.k === 'pharmRx'" />
  <LabOrders v-else-if="page.k === 'labOrders'" />
  <WorkOverview v-else-if="page.k === 'overview'" />
  <AddBusiness v-else-if="page.k === 'add'" />
  <MyAmbulances v-else-if="page.k === 'amb'" />
  <AmbulanceForm v-else-if="page.k === 'ambForm'" :key="page.id ?? 'new'" :id="page.id ?? null" />
  <template v-else-if="page.k === 'bizHome'">
    <Redirect v-if="bizApproved" :to="`/work/${page.id}/orders${loc.search}`" :state="userState(loc.state)" />
    <MyPharmacy v-else-if="page.id === 'pharmacy'" />
    <MyLab v-else />
  </template>
  <PharmacyFinds v-else-if="page.k === 'finds'" />
  <MyPharmacy v-else-if="page.k === 'pharm'" />
  <PharmacyForm v-else-if="page.k === 'pharmForm'" />
  <MyBloodBanks v-else-if="page.k === 'bb'" />
  <BloodBankForm v-else-if="page.k === 'bbForm'" :key="page.id ?? 'new'" :id="page.id ?? null" />
  <MyLab v-else-if="page.k === 'lab'" />
  <LabForm v-else-if="page.k === 'labForm'" />
  <NotFound v-else />
</template>
