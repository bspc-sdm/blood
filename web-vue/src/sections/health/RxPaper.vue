<script setup lang="ts">
// প্রেসক্রিপশনের কাগজ (অ্যাপের RxPaper; print = ছাপার কপি)
import { computed } from 'vue';
import { Tag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { FINDINGS, findingLabel, medHowTo, medTitle, patientLine, specialtyLabel, type Rx } from './rx';
import QrWithCode from './QrWithCode.vue';
import PaperBlock from './PaperBlock.vue';

const props = defineProps<{ rx: Rx; print?: boolean }>();
const d = computed(() => props.rx.doctor ?? {});
const ch = computed(() => d.value.chamber ?? null);
const findings = computed(() => FINDINGS.filter((k) => props.rx.findings?.[k]).map((k) => findingLabel(k) + ': ' + props.rx.findings[k]));
const verifyUrl = computed(() => `${window.location.origin}/rx/${props.rx.code}`);
</script>

<template>
  <article :class="print ? 'health-paper print' : 'health-paper'" :aria-label="tr('Prescription', 'প্রেসক্রিপশন')">
    <div class="row top">
      <div class="col gap-2 grow">
        <h3 class="t-title-l">{{ d.name }}</h3>
        <span v-if="d.degrees" class="t-body-m">{{ d.degrees }}</span>
        <span v-if="d.specialties?.length" class="t-body-m health-paper-h">{{ d.specialties.map(specialtyLabel).join(', ') }}</span>
        <span v-if="d.regNo" class="t-body-s muted">BMDC {{ d.regNo }}</span>
        <span v-if="ch && (ch.name || ch.address)" class="t-body-s muted">{{ [ch.name, ch.address, print ? ch.phone : ''].filter(Boolean).join(', ') }}</span>
      </div>
      <QrWithCode :text="verifyUrl" :code="rx.code" :size="print ? 96 : 84" :print="print" />
    </div>
    <Tag v-if="rx.status === 'cancelled'" tone="error">{{ tr('Cancelled', 'বাতিল') }}</Tag>
    <hr class="divider" />
    <div class="row wrap">
      <span class="t-title-s grow">{{ patientLine(rx) }}</span>
      <span class="t-body-s muted">{{ (print ? tr('Date: ', 'তারিখ: ') : '') + formatDate(rx.createdAt) }}</span>
    </div>
    <hr v-if="print" class="divider" />
    <PaperBlock v-if="rx.complaints" :title="tr('Complaints', 'সমস্যা')" :text="rx.complaints" />
    <PaperBlock v-if="findings.length" :title="tr('Findings', 'পরীক্ষায় পাওয়া')" :text="findings.join(' · ')" />
    <PaperBlock v-if="rx.diagnosis" :title="tr('Diagnosis', 'রোগনির্ণয়')" :text="rx.diagnosis" />
    <div v-if="rx.medicines?.length" class="col">
      <span class="health-rx-sign" aria-hidden="true">℞</span>
      <ol class="col health-meds">
        <li v-for="(m, i) in rx.medicines" :key="i" class="row top">
          <span class="t-body-l health-med-n">{{ num(i + 1) }}.</span>
          <div class="col gap-2 grow">
            <span class="t-body-l semi">{{ medTitle(m) }}</span>
            <span class="t-body-m muted">{{ medHowTo(m) }}</span>
            <span v-if="m.note" class="t-body-s muted pre">{{ m.note }}</span>
          </div>
        </li>
      </ol>
    </div>
    <template v-if="rx.tests?.length">
      <PaperBlock :title="tr('Tests', 'পরীক্ষা')" :text="rx.tests.map((t) => '• ' + t).join('\n')" />
      <span v-if="rx.testPlace" class="t-body-s muted">{{ tr('Suggested place: ', 'করাতে পারেন: ') + rx.testPlace + tr(' (or anywhere you like)', ' (চাইলে অন্য জায়গাতেও)') }}</span>
    </template>
    <PaperBlock v-if="rx.advice" :title="tr('Advice', 'পরামর্শ')" :text="rx.advice" />
    <PaperBlock v-if="rx.followUpDate" :title="tr('Come again', 'আবার দেখাবেন')" :text="formatDate(rx.followUpDate) + (rx.followUpNote ? ' · ' + rx.followUpNote : '')" />
    <span class="t-label-s muted">{{ tr('Verify: ', 'যাচাই: ') + rx.code }}</span>
    <span v-if="print" class="t-label-s muted health-paper-foot">{{ tr('Issued on Lifolo. Scan the QR to check it is genuine.', 'Lifolo-তে দেওয়া প্রেসক্রিপশন। আসল কি না দেখতে QR স্ক্যান করুন।') }}</span>
  </article>
</template>
