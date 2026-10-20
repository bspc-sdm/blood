<script setup lang="ts">
// ডাক্তারের লেখা প্রেসক্রিপশনের তালিকা (rx_doctor_list) — অ্যাপের RxDoctorTab; প্যানেলের listView
import { computed, ref } from 'vue';
import { Button, EmptyState, ErrorBox, Icon, IconButton, SkeletonCards, Tag, cx } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { patientLine } from '../../health/rx';
import { statusTag, type DoctorRx } from './model';
import type { Link } from './patients';
import RxCard from './RxCard.vue';

const props = defineProps<{ link: Link }>();
const nav = useNav();
const wide = useMedia('(min-width: 1100px)');
const q = useCall<{ items: DoctorRx[] }>('rx_doctor_list');
const s = ref('');
const shown = computed(() => {
  const t = toAsciiDigits(s.value.trim().toLowerCase());
  return (q.data?.items ?? []).filter((r) => !t || [r.patient?.name, r.diagnosis, r.code].some((x) => String(x ?? '').toLowerCase().includes(t)));
});
const open = (id: string) => nav(props.link('/view/' + id));
const medsText = (rx: DoctorRx) => (rx.medicines ?? []).slice(0, 4).map((m) => m.name).join(', ') + ((rx.medicines?.length ?? 0) > 4 ? ' +' + num(rx.medicines.length - 4) : '');
</script>

<template>
  <div class="col gap-12">
    <div class="row wrap rxd-toolbar">
      <label class="rxd-search">
        <Icon name="search" />
        <input :value="s" :placeholder="tr('Search by name', 'নাম দিয়ে খুঁজুন')" :aria-label="tr('Search by name', 'নাম দিয়ে খুঁজুন')" @input="s = ($event.target as HTMLInputElement).value.slice(0, 40)" />
      </label>
      <span class="grow" />
      <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="q.refetch()" />
      <Button icon="edit_note" @click="nav(link('/write'))">{{ tr('New prescription', 'নতুন প্রেসক্রিপশন') }}</Button>
    </div>
    <SkeletonCards v-if="q.isPending" :n="4" :h="72" />
    <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
    <EmptyState v-else-if="!shown.length" icon="medication" :title="s ? tr('No one found', 'কাউকে পাওয়া যায়নি') : tr('No prescriptions yet', 'এখনো কোনো প্রেসক্রিপশন নেই')" />
    <div v-else-if="wide" class="table-wrap">
      <table class="table rxd-table">
        <thead>
          <tr>
            <th>{{ tr('Date', 'তারিখ') }}</th>
            <th>{{ tr('Patient', 'রোগী') }}</th>
            <th>{{ tr('Diagnosis', 'রোগনির্ণয়') }}</th>
            <th>{{ tr('Medicines', 'ওষুধ') }}</th>
            <th>{{ tr('Status', 'অবস্থা') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rx in shown" :key="rx.id" :class="cx('rxd-click', rx.status === 'cancelled' && 'rxd-dim')" tabindex="0" @click="open(rx.id)" @keydown.enter="open(rx.id)">
            <td class="rxd-nowrap">{{ formatDate(rx.createdAt) }}</td>
            <td class="rxd-td-name"><span class="t-title-s">{{ patientLine(rx) || '-' }}</span></td>
            <td class="rxd-td-wrap"><template v-if="rx.diagnosis">{{ rx.diagnosis }}</template><span v-else class="muted">—</span></td>
            <td class="rxd-td-wrap muted">{{ medsText(rx) }}</td>
            <td>
              <span class="row wrap gap-6">
                <Tag :tone="rx.status === 'cancelled' ? 'error' : 'good'">{{ statusTag(rx.status) }}</Tag>
                <Tag v-if="rx.linked" tone="primary">{{ tr('In app', 'অ্যাপে') }}</Tag>
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="grid-cards">
      <RxCard v-for="rx in shown" :key="rx.id" :rx="rx" :on-click="() => open(rx.id)" />
    </div>
  </div>
</template>
