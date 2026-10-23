<script setup lang="ts">
// পেশাদারের কাজের জায়গা (/work/pro/*) — অ্যাপের ChamberHubScreen / AppointmentsList(role = provider) / PatientsList / ChamberEditScreen /
// MyHomeCareCard। আমার কাজ CONTRACT §২: ডাক্তারের এক সারি ট্যাব (DoctorNav, প্রেসক্রিপশনের পাতাতেও একই) —
//   আজকের রোগী (/work/pro — আগের "আজকের সিরিয়াল" আর "আজকের ডেস্ক" এক সারিতে, TodayQueue) · বুকিং (/work/pro/bookings) ·
//   রোগী (/work/rx; প্রেসক্রিপশন না লিখলে /work/pro/patients) · প্রেসক্রিপশন (/work/rx/list); সেটআপ, বাসায় সেবা … "আরও"-তে।
// পুরোনো লিংক /work/pro?v=… (বুকিংয়ের ভাগ) → /work/pro/bookings?v=…। নার্স: বাসায় সেবা · সেটআপ (আগের মতো)।
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { Button, EmptyState, PillTabs, SkeletonCards } from '@/ui/kit';
import Redirect from '@/shell/Redirect.vue';
import Workspace from '@/shell/Workspace.vue';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { useNav, useSearchParams } from '@/lib/nav';
import { badgeLabel, isChamberType, isProviderType, parseAppointment, parseProvider, useCatalog } from '../model';
import { systemTag, writesRx } from '@/lib/practice';
import { practitionerLabel } from '@/lib/titleGuard';
import { useAreasReady } from '../shared';
import ChamberEdit from './ChamberEdit.vue';
import BookingsTab from './BookingsTab.vue';
import PatientsTab from './PatientsTab.vue';
import HomeCareTab from './HomeCareTab.vue';
import DoctorNav from './DoctorNav.vue';
import DoctorMore from './DoctorMore.vue';
import TodayQueue from '../TodayQueue.vue';
import DoctorPreview from './DoctorPreview.vue';

const props = withDefaults(defineProps<{ base?: string }>(), { base: '/work/pro' });
useCatalog();
useAreasReady();
const nav = useNav();
const route = useRoute();
const s = useSession();
const pm = useCall<{ provider: any | null; card?: any | null }>('provider_me', {}, () => ({ enabled: s.ready && !s.guest }));
const provider = computed(() => (pm.data?.provider ? parseProvider(pm.data.provider) : null));
const type = computed(() => {
  const me = s.me;
  const badge = me?.badge && me.badge.status === 'approved' && isProviderType(me.badge.type) ? me.badge.type : null;
  const p = provider.value;
  return s.work.professional ?? badge ?? (p && isProviderType(p.type) ? p.type : null);
});
const nurse = computed(() => type.value === 'nurse');
const chamberType = computed(() => isChamberType(type.value));
const appts = useCall<{ items: any[] }>('my_appointments', {}, () => ({ enabled: s.ready && !s.guest && chamberType.value }));
const mine = computed(() => (appts.data?.items ?? []).map(parseAppointment).filter((a) => a.role === 'provider'));

const sub = computed(() => (route.path.startsWith(props.base) ? route.path.slice(props.base.length).replace(/\/$/, '') : ''));
const title = computed(() => (nurse.value ? tr('My home care', 'আমার বাসায় সেবা') : tr('My chamber', 'আমার চেম্বার')));

// নার্স: বাসায় সেবা, সেটআপ (নিজের দুই ট্যাব); বাকিরা DoctorNav-এর এক সারি
const nurseTabs = computed(() => [
  { key: '', label: tr('Home care', 'বাসায় সেবা'), icon: 'home_health' },
  { key: '/chamber', label: tr('Setup', 'সেটআপ'), icon: 'settings_outline' },
]);
const [sp] = useSearchParams();
const KNOWN = ['', '/bookings', '/patients', '/chamber', '/home'];
const cur = computed(() => (nurse.value ? (sub.value === '/chamber' ? '/chamber' : '') : KNOWN.includes(sub.value) ? sub.value : ''));
/** পুরোনো ?v= (বুকিংয়ের ভাগ) আজকের রোগীর পাতায় এলে বুকিংয়ে */
const oldView = computed(() => (cur.value === '' && !nurse.value && sp.value.get('v') ? `${props.base}/bookings?v=${encodeURIComponent(sp.value.get('v')!)}` : null));
const docTab = computed(() => (cur.value === '' ? 'today' : cur.value === '/bookings' ? 'bookings' : cur.value === '/patients' ? 'patients' : 'more'));
const subPage = computed(() => (cur.value === '/chamber' ? tr('Setup', 'সেটআপ') : cur.value === '/home' ? tr('Home care', 'বাসায় সেবা') : ''));
const go = (k: string) => nav(props.base + k);
const apptError = computed(() => (appts.error ? errorText(appts.error) : null));
function chamberSaved() {
  appts.refetch();
  go('');
}
</script>

<template>
  <Workspace v-if="!s.ready || (!s.guest && !type && pm.isLoading)" :title="title">
    <SkeletonCards :n="3" :h="120" />
  </Workspace>
  <Workspace v-else-if="s.guest" :title="title">
    <EmptyState icon="stethoscope" :title="title">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
  </Workspace>
  <Workspace v-else-if="!type" :title="title">
    <EmptyState icon="workspace_premium" :title="tr('Only verified professionals can add chambers', 'শুধু যাচাই করা পেশাজীবীরা চেম্বার যোগ করতে পারেন')">
      <RouterLink class="btn tonal" to="/profile">{{ tr('Profile', 'প্রোফাইল') }}</RouterLink>
    </EmptyState>
  </Workspace>
  <Workspace v-else :title="title">
    <template #intro>
      <div class="col gap-2">
        <h1 class="t-headline appt-h1">{{ title }}</h1>
        <span class="t-body-m muted">{{ [provider?.name, practitionerLabel(provider ?? { type, system: s.me?.badge?.system }) ?? badgeLabel(type), systemTag(type, provider?.system ?? s.me?.badge?.system)].filter(Boolean).join(' · ') }}</span>
      </div>
    </template>
    <template v-if="!nurse" #actions><DoctorMore :type="type" /></template>
    <template v-if="nurse">
      <div class="appt-ws-tabs">
        <PillTabs :tabs="nurseTabs" :value="cur" :label="title" @change="go" />
      </div>
      <ChamberEdit v-if="cur === '/chamber'" :type="type" @saved="go('')" />
      <HomeCareTab v-else :on-setup="() => go('/chamber')" />
    </template>
    <Redirect v-else-if="oldView" :to="oldView" />
    <!-- প্রেসক্রিপশন লেখেন: রোগীর একটাই তালিকা /work/rx -->
    <Redirect v-else-if="cur === '/patients' && writesRx(type)" to="/work/rx" />
    <template v-else>
      <DoctorNav :type="type" :active="docTab" :page="subPage" />
      <PatientsTab v-if="cur === '/patients'" :list="mine" :loading="appts.isLoading" :error="apptError" />
      <ChamberEdit v-else-if="cur === '/chamber'" :type="type" @saved="chamberSaved" />
      <HomeCareTab v-else-if="cur === '/home'" :on-setup="() => go('/chamber')" />
      <BookingsTab v-else-if="cur === '/bookings'" :list="mine" :loading="appts.isLoading" :error="apptError" :reload="() => appts.refetch()" :has-chamber="!!provider?.chambers.length" :on-setup="() => go('/chamber')" />
      <template v-else>
        <TodayQueue :doctor-uid="null" :can-write="writesRx(type)" />
        <!-- "রোগীরা আপনাকে যেভাবে দেখেন" + আজকের চেম্বারে "আজ সিরিয়াল চলছে / আজ বন্ধ" — কাজের নিচে -->
        <DoctorPreview :fallback-card="pm.data?.card ?? pm.data?.provider ?? null" />
      </template>
    </template>
  </Workspace>
</template>
