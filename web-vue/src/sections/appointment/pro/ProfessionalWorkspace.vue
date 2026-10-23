<script setup lang="ts">
// পেশাদারের কাজের জায়গা (/work/pro/*): বুকিং (সিরিয়ালের সারি), রোগী, চেম্বার ও প্রোফাইল সেটআপ, বাসায় সেবার হিসাব —
// অ্যাপের ChamberHubScreen / AppointmentsList(role = provider) / PatientsList / ChamberEditScreen / MyHomeCareCard।
// কম্পিউটারে চেম্বারের সহকারী বা ডাক্তার ব্যবহার করবেন — তাই চওড়া টেবিল। প্রেসক্রিপশন, টিম, সহকারী: /work/rx।
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { Button, EmptyState, Icon, PillTabs, SkeletonCards } from '@/ui/kit';
import Workspace from '@/shell/Workspace.vue';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { CHAMBER_TYPES, HOME_TYPES, PROVIDER_TYPES, badgeLabel, parseAppointment, parseProvider, useCatalog } from '../model';
import { useAreasReady } from '../shared';
import ChamberEdit from './ChamberEdit.vue';
import BookingsTab from './BookingsTab.vue';
import PatientsTab from './PatientsTab.vue';
import HomeCareTab from './HomeCareTab.vue';
import DoctorPanelCard from './DoctorPanelCard.vue';
import { T } from '../../invoices/text';

const props = withDefaults(defineProps<{ base?: string }>(), { base: '/work/pro' });
useCatalog();
useAreasReady();
const nav = useNav();
const route = useRoute();
const s = useSession();
const pm = useCall<{ provider: any | null }>('provider_me', {}, () => ({ enabled: s.ready && !s.guest }));
const provider = computed(() => (pm.data?.provider ? parseProvider(pm.data.provider) : null));
const type = computed(() => {
  const me = s.me;
  const badge = me?.badge && me.badge.status === 'approved' && PROVIDER_TYPES.includes(me.badge.type) ? me.badge.type : null;
  const p = provider.value;
  return s.work.professional ?? badge ?? (p && PROVIDER_TYPES.includes(p.type) ? p.type : null);
});
const nurse = computed(() => type.value === 'nurse');
const chamberType = computed(() => !!type.value && CHAMBER_TYPES.includes(type.value));
const appts = useCall<{ items: any[] }>('my_appointments', {}, () => ({ enabled: s.ready && !s.guest && chamberType.value }));
const mine = computed(() => (appts.data?.items ?? []).map(parseAppointment).filter((a) => a.role === 'provider'));
const pending = computed(() => mine.value.filter((a) => a.status === 'pending').length);

const sub = computed(() => (route.path.startsWith(props.base) ? route.path.slice(props.base.length).replace(/\/$/, '') : ''));
const title = computed(() => (nurse.value ? tr('My home care', 'আমার বাসায় সেবা') : tr('My chamber', 'আমার চেম্বার')));

// ট্যাব: ডাক্তার/পুষ্টিবিদ/ট্রেইনার — বুকিং, রোগী, সেটআপ (+ ডাক্তারের বাসায় সেবা); নার্স — বাসায় সেবা, সেটআপ
const tabs = computed(() =>
  nurse.value
    ? [
        { key: '', label: tr('Home care', 'বাসায় সেবা'), icon: 'home_health' },
        { key: '/chamber', label: tr('Setup', 'সেটআপ'), icon: 'settings_outline' },
      ]
    : [
        { key: '', label: tr('Bookings', 'বুকিং'), icon: 'event_outline', selectedIcon: 'event_filled', count: pending.value },
        { key: '/patients', label: tr('Patients', 'রোগী'), icon: 'person_outline' },
        { key: '/chamber', label: tr('Setup', 'সেটআপ'), icon: 'settings_outline' },
        ...(type.value && HOME_TYPES.includes(type.value) ? [{ key: '/home', label: tr('Home care', 'বাসায় সেবা'), icon: 'home_health' }] : []),
      ],
);
const cur = computed(() => (tabs.value.some((t) => t.key === sub.value) ? sub.value : ''));
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
        <span class="t-body-m muted">{{ [provider?.name, badgeLabel(type)].filter(Boolean).join(' · ') }}</span>
      </div>
    </template>
    <template v-if="type === 'doctor'" #actions>
      <RouterLink class="btn tonal has-icon" to="/work/rx"><Icon name="edit_note" />{{ tr('Write a prescription', 'প্রেসক্রিপশন লিখুন') }}</RouterLink>
      <RouterLink class="btn outline has-icon" to="/work/rx/desk"><Icon name="event_outline" />{{ T.deskTitle() }}</RouterLink>
    </template>
    <div class="appt-ws-tabs">
      <PillTabs :tabs="tabs" :value="cur" :label="title" @change="go" />
    </div>
    <template v-if="nurse">
      <ChamberEdit v-if="cur === '/chamber'" :type="type" @saved="go('')" />
      <HomeCareTab v-else :on-setup="() => go('/chamber')" />
    </template>
    <PatientsTab v-else-if="cur === '/patients'" :list="mine" :loading="appts.isLoading" :error="apptError" />
    <ChamberEdit v-else-if="cur === '/chamber'" :type="type" @saved="chamberSaved" />
    <HomeCareTab v-else-if="cur === '/home'" :on-setup="() => go('/chamber')" />
    <BookingsTab v-else :list="mine" :loading="appts.isLoading" :error="apptError" :reload="() => appts.refetch()" :has-chamber="!!provider?.chambers.length" :on-setup="() => go('/chamber')" />
    <DoctorPanelCard v-if="type === 'doctor'" />
  </Workspace>
</template>
