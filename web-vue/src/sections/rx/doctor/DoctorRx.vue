<script setup lang="ts">
// প্রেসক্রিপশন — ডাক্তার ও সহকারীর কাজের জায়গা (/work/rx/*), আগের /doctor প্যানেলের বদলে।
// অ্যাপের Prescriptions.kt (RxDoctorTab, DoctorPatientsTab, RxAssistScreen…) আর RxNet.kt (RxReviewsPane) — কম্পিউটারে চওড়া করে।
//   /work/rx               রোগী (আজকের অ্যাপয়েন্টমেন্ট + এন্ট্রি করা রোগী), ?patient= / ?appointment= বাছলে পাশে আগের প্রেসক্রিপশন
//   /work/rx/write         নতুন প্রেসক্রিপশন (?patient= / ?appointment=; না থাকলে রোগী বাছাই)
//   /work/rx/list          আমার লেখা প্রেসক্রিপশন
//   /work/rx/view/:id      একটা প্রেসক্রিপশন: কাগজ, ছাপা, বাতিল
//   /work/rx/reviews       রিপোর্ট দেখার অনুরোধ (সহকারী: টাকা মেলানো)
//   /work/rx/team          সহকারী (শুধু ডাক্তার)
//   /work/rx/desk          পুরোনো ঠিকানা → "আজকের রোগী" (ডাক্তার /work/pro, সহকারী /work/rx)
// সহকারী: ?doctor=<uid> — কোন ডাক্তারের হয়ে; প্রতিটা কাজে doctorUid হিসেবে যায়।
// আমার কাজ CONTRACT §২: ডাক্তার এখানেও "আমার চেম্বার"-এর একই ট্যাব (DoctorNav: আজকের রোগী · বুকিং · রোগী · প্রেসক্রিপশন, বাকিটা "আরও")।
// সহকারী: আজকের রোগী (/work/rx — ডাক্তারের একই সারি, প্রেসক্রিপশন লেখা ছাড়া) · রোগী (/work/rx/patients) · টাকা মেলানো (/work/rx/reviews)।
import { computed, defineAsyncComponent } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { Button, EmptyState, ErrorText, PillTabs, Select, SkeletonCards } from '@/ui/kit';
import Workspace from '@/shell/Workspace.vue';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { matchPath, useNav, useSearchParams } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';
import Redirect from '@/shell/Redirect.vue';
import DoctorNav from '../../appointment/pro/DoctorNav.vue';
import DoctorMore from '../../appointment/pro/DoctorMore.vue';
import type { DocTab } from '../../appointment/pro/doctorNav';
import type { Actor, RxMe } from './model';
import PatientsPage from './PatientsPage.vue';
import WritePage from './WritePage.vue';
import RxListPage from './RxListPage.vue';
import RxViewPage from './RxViewPage.vue';
import ReviewsPage from './ReviewsPage.vue';
import TeamPage from './TeamPage.vue';
import OnlyDoctor from './OnlyDoctor.vue';
import { newTypeLabel, rxKindOf, systemTag, writesRx } from '@/lib/practice';
import { practitionerLabel } from '@/lib/titleGuard';

const DoctorPreview = defineAsyncComponent(() => import('../../appointment/pro/DoctorPreview.vue'));
const TodayQueue = defineAsyncComponent(() => import('../../appointment/TodayQueue.vue'));

const BASE = '/work/rx';
const route = useRoute();
const nav = useNav();
const [sp] = useSearchParams();
const s = useSession();
const title = computed(() => (isDoctor.value && !sp.value.get('doctor') ? tr('My chamber', 'আমার চেম্বার') : tr('Prescriptions', 'প্রেসক্রিপশন')));
// ডাক্তার; ফিজিওথেরাপিস্ট/সাইকোলজিস্ট (সুইচ চালু থাকলে) — নিজের ধরনের প্রেসক্রিপশন (lib/practice.ts rxKind)
const isDoctor = computed(() => writesRx(s.work.professional));
const me = useCall<RxMe>('rx_me', {}, () => ({ enabled: s.ready && !s.guest && isDoctor.value, staleTime: 5 * 60_000 }));
const loading = computed(() => !s.ready || (!s.guest && !s.workLoaded && !s.workError));

// কার হয়ে: ?doctor= (সহকারী), নইলে নিজে (ডাক্তার), নইলে প্রথম ডাক্তার
const actor = computed<Actor | null>(() => {
  const assisting = s.work.assisting;
  const asked = sp.value.get('doctor');
  const pick = asked && asked !== s.uid ? assisting.find((d) => d.doctorUid === asked) : null;
  return pick
    ? { role: 'assistant', doctorUid: pick.doctorUid, doctorName: pick.name }
    : isDoctor.value
      ? { role: 'doctor', doctorUid: null, doctorName: me.data?.name ?? s.profile?.name ?? '' }
      : assisting.length
        ? { role: 'assistant', doctorUid: assisting[0].doctorUid, doctorName: assisting[0].name }
        : null;
});
const doctor = computed(() => actor.value?.role === 'doctor');
const prof = computed(() => s.work.professional ?? 'doctor');
const kind = computed(() => rxKindOf(prof.value, me.data?.system ?? s.me?.badge?.system, me.data?.rxRules));
/** কাগজে নামের নিচে: পদ্ধতি (ডাক্তার, সুইচ চালু থাকলে) বা পেশা (নতুন ধরন) */
const headTag = computed(
  () => practitionerLabel({ type: prof.value, system: me.data?.system ?? s.me?.badge?.system, typeLabel: me.data?.typeLabel, typeLabelEn: me.data?.typeLabelEn }) ?? newTypeLabel(prof.value) ?? systemTag(prof.value, me.data?.system ?? s.me?.badge?.system),
);
/** ?doctor= রেখে অন্য পাতায় */
const link = (path: string, extra: Record<string, string> = {}) => {
  const q = new URLSearchParams(extra);
  const a = actor.value;
  if (a?.role === 'assistant' && a.doctorUid) q.set('doctor', a.doctorUid);
  const qs = q.toString();
  return BASE + path + (qs ? '?' + qs : '');
};
const sub = computed(() => (route.path.startsWith(BASE) ? route.path.slice(BASE.length).replace(/\/$/, '') : ''));
const m = (p: string) => matchPath({ path: p, end: true }, sub.value || '/');
// সহকারীর ট্যাব (ডাক্তার DoctorNav)
const tabs = computed(() => [
  { key: '', label: tr("Today's patients", 'আজকের রোগী'), icon: 'event_outline', selectedIcon: 'event_filled' }, // bn-ok
  { key: '/patients', label: tr('Patients', 'রোগী'), icon: 'person_outline', selectedIcon: 'person_filled' },
  { key: '/reviews', label: tr('Payments', 'টাকা মেলানো'), icon: 'payments' },
]);
const cur = computed(() => (tabs.value.some((t) => t.key === sub.value) ? sub.value : ''));
const page = computed<{ k: string; id?: string }>(() => {
  const view = m('/view/:id');
  if (!sub.value || m('/')) return { k: doctor.value ? 'patients' : 'today' };
  if (m('/patients')) return { k: 'patients' };
  if (m('/write')) return { k: doctor.value ? 'write' : 'only' };
  if (m('/list')) return { k: doctor.value ? 'list' : 'only' };
  if (view) return doctor.value ? { k: 'view', id: view.params.id ?? '' } : { k: 'only' };
  if (m('/reviews')) return { k: 'reviews' };
  if (m('/team')) return { k: doctor.value ? 'team' : 'only' };
  if (m('/desk')) return { k: 'desk' };
  return { k: 'none' };
});
const docTab = computed<DocTab>(() => (page.value.k === 'patients' ? 'patients' : ['write', 'list', 'view'].includes(page.value.k) ? 'rx' : 'more'));
const subPage = computed(() => (page.value.k === 'reviews' ? tr('Reports', 'রিপোর্ট') : page.value.k === 'team' ? tr('Assistants', 'সহকারী') : ''));
// কার হয়ে কাজ: নিজে (ডাক্তার হলে) আর যাঁদের সহকারী
const options = computed(() => [
  ...(isDoctor.value ? [{ value: '', label: tr('Myself (doctor)', 'নিজে (ডাক্তার)') }] : []),
  ...s.work.assisting.map((d) => ({ value: d.doctorUid, label: tr(`Assistant · ${d.name}`, `সহকারী · ${d.name}`) })),
]);
const selected = computed(() => (actor.value?.role === 'doctor' ? '' : actor.value?.doctorUid ?? ''));
const whoLine = computed(() => {
  const a = actor.value!;
  return doctor.value ? [a.doctorName, me.data?.degrees].filter(Boolean).join(' · ') : tr(`Assistant of ${a.doctorName}`, `${a.doctorName}-এর সহকারী`);
});
// নিজে আর সহকারীর কাজ আলাদা — রোগীর তালিকায় ফেরা
const pickWho = (v: string) => nav(BASE + (v ? `?doctor=${encodeURIComponent(v)}` : ''));
</script>

<template>
  <Workspace v-if="loading" :title="title"><SkeletonCards :n="3" :h="88" /></Workspace>
  <Workspace v-else-if="s.guest" :title="title">
    <EmptyState icon="medication" :title="title" :text="tr('For verified doctors and their assistants', 'যাচাই করা ডাক্তার আর তাঁদের সহকারীর জন্য')">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
  </Workspace>
  <Workspace v-else-if="!actor" :title="title">
    <ErrorText v-if="s.workError" :text="s.workError" />
    <EmptyState
      icon="medication"
      :title="tr('Only verified doctors and their assistants can use this', 'শুধু যাচাই করা ডাক্তার আর তাঁদের সহকারী এটা ব্যবহার করতে পারেন')"
      :text="tr('Are you a doctor\'s assistant? Ask the doctor to add your email as an assistant.', 'ডাক্তারের সহকারী? ডাক্তারকে বলুন আপনার ইমেইল সহকারী হিসেবে যোগ করতে।')"
    >
      <RouterLink class="btn tonal" to="/work">{{ tr('My work', 'আমার কাজ') }}</RouterLink>
    </EmptyState>
  </Workspace>
  <NotFound v-else-if="page.k === 'none'" />
  <Workspace v-else :title="title">
    <template #intro>
      <div class="col gap-2">
        <h1 class="t-headline rxd-h1">{{ title }}</h1>
        <span class="t-body-m muted">{{ whoLine }}</span>
      </div>
    </template>
    <template v-if="options.length > 1 || doctor" #actions>
      <div v-if="options.length > 1" class="rxd-who">
        <Select :aria-label="tr('Working for', 'কার হয়ে কাজ')" :model-value="selected" :options="options" @update:model-value="pickWho" />
      </div>
      <DoctorMore v-if="doctor" :type="prof" />
    </template>
    <DoctorNav v-if="doctor" :type="prof" :active="docTab" :page="subPage" />
    <div v-else class="q-fixed">
      <PillTabs :tabs="tabs" :value="cur" :label="title" @change="(k) => nav(link(k))" />
    </div>
    <!-- পুরোনো "আজকের ডেস্ক" → আজকের রোগী -->
    <Redirect v-if="page.k === 'desk'" :to="doctor ? '/work/pro' : link('')" />
    <template v-else-if="page.k === 'today'">
      <TodayQueue :doctor-uid="actor.doctorUid" :can-write="false" />
      <!-- সহকারী: ডাক্তারের "রোগীরা যেভাবে দেখেন" এক লাইনে আর অনুমতির চেম্বারের অবস্থা ("বদলান" → /work) — কাজের নিচে -->
      <DoctorPreview :key="actor.doctorUid ?? ''" :doctor-uid="actor.doctorUid" />
    </template>
    <PatientsPage v-else-if="page.k === 'patients'" :actor="actor" :link="link" />
    <WritePage v-else-if="page.k === 'write'" :kind="kind" :head-tag="headTag" :actor="actor" :chambers="me.data?.chambers ?? []" :degrees="me.data?.degrees ?? ''" :link="link" />
    <RxListPage v-else-if="page.k === 'list'" :link="link" />
    <RxViewPage v-else-if="page.k === 'view'" :id="page.id!" :link="link" />
    <ReviewsPage v-else-if="page.k === 'reviews'" :actor="actor" :link="link" />
    <TeamPage v-else-if="page.k === 'team'" />
    <OnlyDoctor v-else :link="link" />
  </Workspace>
</template>
