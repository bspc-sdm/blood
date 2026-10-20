<script setup lang="ts">
// ডাক্তারের রোগীর ইতিহাসে আর প্রেসক্রিপশনের পাতায় পার্টনার ল্যাবের পরীক্ষা (১৮ অক্টোবর ২০২৬, "ডাক্তারকে রিপোর্ট দেখানো")।
// সার্ভারের doctorLabView: অবস্থা সবসময়; ফল শুধু রিপোর্ট দেখার টাকা মিললে বা "ফি চাই না" হলে (open) — নইলে কেন বন্ধ।
// পুরো রিপোর্ট (ছবি, আগের ফল) lab_order_get দিয়ে, LabReportView। অ্যাপের DoctorLabList (RxNet.kt)।
import { ref } from 'vue';
import { Action, Icon, Tag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { flagLabel, labLocked, labStatus, type DoctorLab } from './model';
import DoctorReportDialog from './DoctorReportDialog.vue';

defineProps<{ labs: DoctorLab[] }>();
const reading = ref<DoctorLab | null>(null);
const flaggedOf = (o: DoctorLab) => (o.report?.items ?? []).filter((it) => it.flag && !it.long);
const flaggedText = (o: DoctorLab) => {
  const flagged = flaggedOf(o);
  return (
    tr('High/low: ', 'বেশি/কম: ') +
    flagged.slice(0, 4).map((it) => `${it.test} ${it.result}${it.unit ? ' ' + it.unit : ''} (${flagLabel(it.flag)})`).join(', ') +
    (flagged.length > 4 ? ' +' + num(flagged.length - 4) : '')
  );
};
</script>

<template>
  <div v-if="labs.length" class="col gap-6 rxd-labs">
    <div v-for="o in labs" :key="o.id" class="col gap-2 rxd-lab">
      <div class="row top gap-6">
        <span class="t-body-l semi grow">{{ o.labName || tr('Lab', 'প্যাথলজি') }}</span>
        <Tag :tone="o.status === 'reported' ? 'good' : o.status === 'cancelled' ? 'error' : undefined">{{ labStatus(o.status) }}</Tag>
      </div>
      <span class="t-body-s muted">{{ [o.tests.join(', '), formatDate(o.reportedAt || o.createdAt)].filter(Boolean).join(' · ') }}</span>
      <template v-if="o.status === 'reported' && o.open">
        <span v-if="flaggedOf(o).length" class="t-body-s c-error">{{ flaggedText(o) }}</span>
        <span v-else-if="(o.report?.items ?? []).length" class="t-body-s muted">{{ tr(`${o.report!.items.length} result(s)`, `${num(o.report!.items.length)}টা ফল`) }}</span>
        <span v-if="o.review === 'paid'" class="t-body-s muted">{{ tr('Not replied yet — reply from Reports', 'রোগীকে এখনো জানাননি — "রিপোর্ট" থেকে জানান') }}</span>
        <span v-if="o.comment" class="t-body-s pre">{{ tr('You told the patient: ', 'রোগীকে জানিয়েছিলেন: ') + o.comment }}</span>
        <div><Action primary icon="list_alt" @click="reading = o">{{ tr('See report', 'রিপোর্ট দেখুন') }}</Action></div>
      </template>
      <span v-else-if="o.status === 'reported'" class="row gap-6 t-body-s muted rxd-lab-locked"><Icon name="privacy_tip" />{{ labLocked(o.review) }}</span>
    </div>
    <DoctorReportDialog v-if="reading" :o="reading" @close="reading = null" />
  </div>
</template>
