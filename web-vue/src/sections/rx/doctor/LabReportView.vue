<script setup lang="ts">
// রিপোর্ট: পরীক্ষা ধরে ভাগ, বেশি/কম চিহ্ন, একই রোগীর আগের ফল (অ্যাপের LabReportView) — রোগীর ইতিহাসেও (DoctorLabList)
import { computed } from 'vue';
import { Tag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { imgSrc } from '../../health/common';
import { ageText, flagLabel, type LabHistory, type LabOrder, type LabResult } from './model';

const props = defineProps<{ o: LabOrder; history: LabHistory }>();
const rep = computed(() => props.o.report);
const groups = computed(() => {
  const out: { name: string; rows: LabResult[] }[] = [];
  for (const it of rep.value?.items ?? []) {
    const g = it.group ?? '';
    if (out.length && out[out.length - 1].name === g) out[out.length - 1].rows.push(it);
    else out.push({ name: g, rows: [it] });
  }
  return out;
});
const prevOf = (it: LabResult) => (it.code ? props.history[`${it.code}.${it.key}`] ?? [] : []);
const hasPrev = computed(() => (rep.value?.items ?? []).some((it) => prevOf(it).length));
const prevText = (it: LabResult) => {
  const prev = prevOf(it);
  return prev.length ? prev.slice(0, 3).map((h) => num(h.result) + (h.flag === 'high' ? '↑' : h.flag === 'low' ? '↓' : '') + ' (' + formatDate(h.at) + ')').join(', ') : '—';
};
</script>

<template>
  <div class="col gap-12">
    <div class="col gap-2">
      <span class="t-title-m">{{ o.labName }}</span>
      <span class="t-body-s muted">{{ [o.patientName, ageText(o.patientAge), rep?.reportedAt ? formatDate(rep.reportedAt) : null].filter(Boolean).join(' · ') }}</span>
    </div>
    <div v-for="(g, gi) in groups" :key="gi" class="table-wrap">
      <div v-if="g.name" class="rxd-rep-group">{{ g.name }}</div>
      <table class="table rxd-rep">
        <thead>
          <tr>
            <th>{{ tr('Test', 'পরীক্ষা') }}</th>
            <th>{{ tr('Result', 'ফল') }}</th>
            <th>{{ tr('Normal', 'স্বাভাবিক') }}</th>
            <th v-if="hasPrev">{{ tr('Before', 'আগে') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(it, i) in g.rows" :key="i">
            <td class="t-title-s">{{ it.test }}</td>
            <td :class="it.long ? 'pre' : 'rxd-nowrap'"><span :class="it.flag ? 'semi c-error' : 'semi'">{{ it.result + (it.unit ? ' ' + it.unit : '') }}</span><template v-if="it.flag">{{ ' ' }}<Tag tone="error">{{ flagLabel(it.flag) }}</Tag></template></td>
            <td class="muted">{{ it.range || '—' }}</td>
            <td v-if="hasPrev" class="t-body-s muted">{{ prevText(it) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="rep?.note" class="t-body-m pre">{{ rep.note }}</p>
    <img v-for="(p, i) in rep?.photos ?? []" :key="i" :src="imgSrc(p)" :alt="tr('Report photo', 'রিপোর্টের ছবি')" class="rxd-photo" />
  </div>
</template>
