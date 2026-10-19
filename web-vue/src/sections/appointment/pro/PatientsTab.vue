<script setup lang="ts">
// রোগী: একজন একবার — কতবার এসেছেন, শেষ কবে, ফোন
import { computed, ref } from 'vue';
import { EmptyState, ErrorBox, PhoneLine, SkeletonCards } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { formatDate } from '@/lib/format';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import type { Appointment } from '../model';

const props = defineProps<{ list: Appointment[]; loading: boolean; error: string | null }>();
const wide = useMedia('(min-width: 1100px)');
const q = ref('');
const pats = computed(() => {
  const m = new Map<string, Appointment[]>();
  props.list
    .filter((a) => a.status === 'confirmed' || a.status === 'completed')
    .forEach((a) => {
      const k = a.otherKey || a.otherName;
      m.set(k, [...(m.get(k) ?? []), a]);
    });
  return [...m.values()]
    .map((a) => ({ name: a[0].otherName, count: a.length, last: Math.max(...a.map((x) => x.date)), phone: a.find((x) => x.otherPhone)?.otherPhone ?? '' }))
    .sort((x, y) => y.last - x.last);
});
const shown = computed(() => {
  const t = q.value.trim().toLowerCase();
  return pats.value.filter((p) => !t || p.name.toLowerCase().includes(t) || p.phone.includes(toAsciiDigits(t)));
});
const visits = (p: { count: number; last: number }) => tr(`${p.count} visit(s) · last `, `${num(p.count)} বার · শেষ `) + formatDate(p.last);
</script>

<template>
  <SkeletonCards v-if="loading" :n="4" :h="64" />
  <ErrorBox v-else-if="error" :error="error" />
  <EmptyState v-else-if="!pats.length" icon="person_outline" :title="tr('No patients yet', 'এখনো কোনো রোগী নেই')" />
  <div v-else class="col gap-12">
    <input v-model="q" class="input appt-search" :placeholder="tr('Search by name', 'নাম দিয়ে খুঁজুন')" :aria-label="tr('Search by name', 'নাম দিয়ে খুঁজুন')" />
    <div v-if="wide" class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>{{ tr('Patient', 'রোগী') }}</th>
            <th>{{ tr('Visits', 'কতবার') }}</th>
            <th>{{ tr('Last', 'শেষ') }}</th>
            <th>{{ tr('Phone', 'ফোন') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(p, i) in shown" :key="i">
            <td class="t-title-s">{{ p.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট') }}</td>
            <td class="num">{{ num(p.count) }}</td>
            <td class="appt-nowrap">{{ formatDate(p.last) }}</td>
            <td class="appt-phone-cell"><PhoneLine v-if="p.phone" :phone="p.phone" /><span v-else class="muted">—</span></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="grid-cards appt-grid">
      <article v-for="(p, i) in shown" :key="i" class="card col gap-6">
        <span class="t-title-m">{{ p.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট') }}</span>
        <span class="t-body-s muted">{{ visits(p) }}</span>
        <PhoneLine v-if="p.phone" :phone="p.phone" />
      </article>
    </div>
  </div>
</template>
