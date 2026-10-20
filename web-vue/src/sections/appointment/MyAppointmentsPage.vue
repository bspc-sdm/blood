<script setup lang="ts">
// আমার অ্যাপয়েন্টমেন্ট (রোগী হিসেবে): সামনের আর আগের, সিরিয়াল, অবস্থা, বাতিল (অ্যাপের MyAppointmentsScreen)।
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, EmptyState, ErrorBox, IconButton, SkeletonCards, toast } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import ListDetail from '@/shell/ListDetail.vue';
import SubPageBar from '@/shell/SubPageBar.vue';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { formatDate } from '@/lib/format';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { isActive, parseAppointment, today, type Appointment } from './model';
import ApptCard from './ApptCard.vue';
import ApptDetail from './ApptDetail.vue';

const props = defineProps<{ detailId: string | null }>();
const title = computed(() => tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট'));
usePageTitle(title);
const nav = useNav();
const two = useTwoPane();
const s = useSession();
const q = useCall<{ items: any[] }>('my_appointments', {}, () => ({ enabled: s.ready && !s.guest }));
const mine = computed(() => (q.data?.items ?? []).map(parseAppointment).filter((a) => a.role === 'patient'));
const upcoming = computed(() => {
  const t0 = today();
  return mine.value.filter((a) => isActive(a.status) && a.date >= t0).sort((a, b) => a.date - b.date || a.serial - b.serial);
});
const past = computed(() => mine.value.filter((a) => !upcoming.value.includes(a)).sort((a, b) => b.date - a.date || b.createdAt - a.createdAt));
const selected = computed(() => (props.detailId ? mine.value.find((a) => a.id === props.detailId) ?? null : null));
const cancelling = ref<Appointment | null>(null);
const busy = ref(false);
const err = ref<string | null>(null);

async function cancel(a: Appointment) {
  busy.value = true;
  err.value = null;
  try {
    await call('appointment_update', { id: a.id, op: 'cancel', providerNote: '' });
    cancelling.value = null;
    toast(tr('Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'));
    await q.refetch();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function askCancel(a: Appointment) {
  err.value = null;
  cancelling.value = a;
}
</script>

<template>
  <SubPageBar v-if="!detailId || two" :title="title" back-to="/profile">
    <template v-if="!s.guest" #actions><IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="q.refetch()" /></template>
  </SubPageBar>
  <ListDetail :has-detail="!!detailId">
    <div v-if="s.guest" class="list-inner">
      <EmptyState icon="calendar_month" :title="title">
        <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
      </EmptyState>
    </div>
    <div v-else class="list-inner">
      <SkeletonCards v-if="q.isLoading || !s.ready" :n="3" :h="140" />
      <ErrorBox v-else-if="q.error" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
      <EmptyState v-else-if="!mine.length" icon="calendar_month" :title="tr('No appointments', 'কোনো অ্যাপয়েন্টমেন্ট নেই')">
        <Button variant="tonal" icon="stethoscope" @click="nav('/appointment')">{{ tr('Appointment', 'অ্যাপয়েন্টমেন্ট') }}</Button>
      </EmptyState>
      <template v-else>
        <h2 v-if="upcoming.length" class="section-label appt-sec">{{ tr('Upcoming', 'সামনের') }}</h2>
        <ApptCard v-for="a in upcoming" :key="a.id" :a="a" :selected="a.id === detailId" :on-click="() => nav(`/my-appointments/${a.id}`)" />
        <h2 v-if="past.length" class="section-label appt-sec">{{ tr('Past', 'আগের') }}</h2>
        <ApptCard v-for="a in past" :key="a.id" :a="a" :selected="a.id === detailId" :on-click="() => nav(`/my-appointments/${a.id}`)" />
      </template>
    </div>
    <template #detail>
      <DetailPane :title="selected?.otherName || title" back-to="/my-appointments">
        <template v-if="!two" #actions><IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="q.refetch()" /></template>
        <ApptDetail v-if="selected" :a="selected" @cancel="askCancel(selected)" />
        <SkeletonCards v-else-if="q.isLoading" :n="1" :h="200" />
        <EmptyState v-else icon="calendar_month" :title="tr('Not found', 'পাওয়া যায়নি')" />
      </DetailPane>
    </template>
    <template #placeholder><EmptyState icon="calendar_month" :title="title" /></template>
  </ListDetail>
  <ConfirmDialog
    v-if="cancelling"
    :title="tr('Cancel appointment?', 'অ্যাপয়েন্টমেন্ট বাতিল করবেন?')"
    :text="`${cancelling.otherName} · ${formatDate(cancelling.date)}`"
    :confirm="tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল')"
    danger
    :busy="busy"
    :error="err"
    @confirm="cancel(cancelling!)"
    @close="() => !busy && (cancelling = null)"
  />
</template>
