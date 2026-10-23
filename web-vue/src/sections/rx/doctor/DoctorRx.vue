<script setup lang="ts">
// প্রেসক্রিপশন — ডাক্তার ও সহকারীর কাজের জায়গা (/work/rx/*), আগের /doctor প্যানেলের বদলে।
// অ্যাপের Prescriptions.kt (RxDoctorTab, DoctorPatientsTab, RxAssistScreen…) আর RxNet.kt (RxReviewsPane) — কম্পিউটারে চওড়া করে।
//   /work/rx               রোগী (আজকের অ্যাপয়েন্টমেন্ট + এন্ট্রি করা রোগী), ?patient= / ?appointment= বাছলে পাশে আগের প্রেসক্রিপশন
//   /work/rx/write         নতুন প্রেসক্রিপশন (?patient= / ?appointment=; না থাকলে রোগী বাছাই)
//   /work/rx/list          আমার লেখা প্রেসক্রিপশন
//   /work/rx/view/:id      একটা প্রেসক্রিপশন: কাগজ, ছাপা, বাতিল
//   /work/rx/reviews       রিপোর্ট দেখার অনুরোধ (সহকারী: টাকা মেলানো)
//   /work/rx/team          সহকারী (শুধু ডাক্তার)
//   /work/rx/desk          চেম্বারের ডেস্ক: আজকের সিরিয়াল, চেক-ইন, সরাসরি আসা রোগী, ফি → রসিদ, আজকের আদায় (ডাক্তার ও সহকারী)
// সহকারী: ?doctor=<uid> — কোন ডাক্তারের হয়ে; প্রতিটা কাজে doctorUid হিসেবে যায়।
import { computed, defineAsyncComponent } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { Button, EmptyState, ErrorText, PillTabs, Select, SkeletonCards } from '@/ui/kit';
import Workspace from '@/shell/Workspace.vue';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { matchPath, useNav, useSearchParams } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';
import type { Actor, RxMe } from './model';
import PatientsPage from './PatientsPage.vue';
import WritePage from './WritePage.vue';
import RxListPage from './RxListPage.vue';
import RxViewPage from './RxViewPage.vue';
import ReviewsPage from './ReviewsPage.vue';
import TeamPage from './TeamPage.vue';
import OnlyDoctor from './OnlyDoctor.vue';
import { T } from '../../invoices/text';
import { invFeature } from '../../invoices/features';
import { newTypeLabel, rxKindOf, systemTag, writesRx } from '@/lib/practice';
import { practitionerLabel } from '@/lib/titleGuard';

const ChamberDesk = defineAsyncComponent(() => import('../../invoices/ChamberDesk.vue'));

const BASE = '/work/rx';
const route = useRoute();
const nav = useNav();
const [sp] = useSearchParams();
const s = useSession();
const title = computed(() => tr('Prescriptions', 'প্রেসক্রিপশন'));
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
const labsOn = computed(() => kind.value === 'full' || kind.value === 'free');
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
const tabs = computed(() =>
  doctor.value
    ? [
        { key: '', label: tr('Patients', 'রোগী'), icon: 'person_outline', selectedIcon: 'person_filled' },
        { key: '/write', label: tr('New prescription', 'নতুন প্রেসক্রিপশন'), icon: 'edit_note' },
        { key: '/list', label: tr('Prescriptions', 'প্রেসক্রিপশন'), icon: 'medication' },
        ...(labsOn.value ? [{ key: '/reviews', label: tr('Reports', 'রিপোর্ট'), icon: 'list_alt' }] : []),
        { key: '/team', label: tr('Assistants', 'সহকারী'), icon: 'group_round' },
        ...(invFeature('inv_chamber') ? [{ key: '/desk', label: T.desk(), icon: 'event_outline' }] : []),
      ]
    : [
        { key: '', label: tr('Patients', 'রোগী'), icon: 'person_outline', selectedIcon: 'person_filled' },
        ...(invFeature('inv_chamber') ? [{ key: '/desk', label: T.desk(), icon: 'event_outline' }] : []),
        { key: '/reviews', label: tr('Payments', 'টাকা মেলানো'), icon: 'payments' },
      ],
);
const cur = computed(() => (m('/view/:id') ? (doctor.value ? '/list' : '') : tabs.value.some((t) => t.key === sub.value) ? sub.value : ''));
const page = computed<{ k: string; id?: string }>(() => {
  const view = m('/view/:id');
  if (!sub.value || m('/')) return { k: 'patients' };
  if (m('/write')) return { k: doctor.value ? 'write' : 'only' };
  if (m('/list')) return { k: doctor.value ? 'list' : 'only' };
  if (view) return doctor.value ? { k: 'view', id: view.params.id ?? '' } : { k: 'only' };
  if (m('/reviews')) return { k: 'reviews' };
  if (m('/team')) return { k: doctor.value ? 'team' : 'only' };
  if (m('/desk') && invFeature('inv_chamber')) return { k: 'desk' };
  return { k: 'none' };
});
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
    <template v-if="options.length > 1" #actions>
      <div class="rxd-who">
        <Select :aria-label="tr('Working for', 'কার হয়ে কাজ')" :model-value="selected" :options="options" @update:model-value="pickWho" />
      </div>
    </template>
    <div class="rxd-tabs">
      <PillTabs :tabs="tabs" :value="cur" :label="title" @change="(k) => nav(link(k))" />
    </div>
    <PatientsPage v-if="page.k === 'patients'" :actor="actor" :link="link" />
    <WritePage v-else-if="page.k === 'write'" :kind="kind" :head-tag="headTag" :actor="actor" :chambers="me.data?.chambers ?? []" :degrees="me.data?.degrees ?? ''" :link="link" />
    <RxListPage v-else-if="page.k === 'list'" :link="link" />
    <RxViewPage v-else-if="page.k === 'view'" :id="page.id!" :link="link" />
    <ReviewsPage v-else-if="page.k === 'reviews'" :actor="actor" :link="link" />
    <TeamPage v-else-if="page.k === 'team'" />
    <ChamberDesk v-else-if="page.k === 'desk'" :doctor-uid="actor.doctorUid" />
    <OnlyDoctor v-else :link="link" />
  </Workspace>
</template>
