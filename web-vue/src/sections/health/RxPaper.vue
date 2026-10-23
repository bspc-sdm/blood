<script setup lang="ts">
// প্রেসক্রিপশনের কাগজ (অ্যাপের RxPaper; print = ছাপার কপি)
import { computed } from 'vue';
import { Tag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { FINDINGS, findingLabel, medHowTo, medTitle, patientLine, specialtyLabel, type Rx } from './rx';
import QrWithCode from './QrWithCode.vue';
import PaperBlock from './PaperBlock.vue';
import { T as PT, isNewType, newTypeLabel, regBody, rxKindOf, systemOf, systemTag, systemsOn } from '@/lib/practice';

const props = defineProps<{ rx: Rx; print?: boolean }>();
const d = computed(() => props.rx.doctor ?? {});
const ch = computed(() => d.value.chamber ?? null);
const findings = computed(() => FINDINGS.filter((k) => props.rx.findings?.[k]).map((k) => findingLabel(k) + ': ' + props.rx.findings[k]));
const verifyUrl = computed(() => `${window.location.origin}/rx/${props.rx.code}`);
// practice: নামের নিচে পদ্ধতি/পেশা; নিবন্ধনের প্রতিষ্ঠান (সুইচ বন্ধ থাকলে আগের মতো "BMDC"); পরামর্শের শিরোনাম
const dtype = computed(() => props.rx.doctorType ?? d.value.type);
const kind = computed(() => rxKindOf(dtype.value, d.value.system, props.rx.rxRules));
const headTag = computed(() => (isNewType(dtype.value) ? newTypeLabel(dtype.value!) : systemTag(dtype.value, d.value.system)));
const regLine = computed(() => (isNewType(dtype.value) ? d.value.regNo : `${systemsOn() ? regBody(systemOf(d.value.system)) : 'BMDC'} ${d.value.regNo}`));
const adviceTitle = computed(() => (kind.value === 'advice' ? PT.adviceExercise() : kind.value === 'notes' ? PT.sessionNotes() : tr('Advice', 'পরামর্শ')));
</script>

<template>
  <article :class="print ? 'health-paper print' : 'health-paper'" :aria-label="tr('Prescription', 'প্রেসক্রিপশন')">
    <div class="row top">
      <div class="col gap-2 grow">
        <h3 class="t-title-l">{{ d.name }}</h3>
        <span v-if="headTag" class="t-body-m health-paper-h" data-testid="paper-system">{{ headTag }}</span>
        <span v-if="d.degrees" class="t-body-m">{{ d.degrees }}</span>
        <span v-if="d.specialties?.length" class="t-body-m health-paper-h">{{ d.specialties.map(specialtyLabel).join(', ') }}</span>
        <span v-if="d.regNo" class="t-body-s muted">{{ regLine }}</span>
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
    <PaperBlock v-if="rx.diagnosis" :title="kind === 'advice' ? PT.assessment() : tr('Diagnosis', 'রোগনির্ণয়')" :text="rx.diagnosis" />
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
    <PaperBlock v-if="rx.advice" :title="adviceTitle" :text="rx.advice" />
    <p v-if="kind === 'notes'" class="t-body-m semi" data-testid="paper-psychiatrist">{{ PT.psychiatrist() }}</p>
    <PaperBlock v-if="rx.followUpDate" :title="tr('Come again', 'আবার দেখাবেন')" :text="formatDate(rx.followUpDate) + (rx.followUpNote ? ' · ' + rx.followUpNote : '')" />
    <span class="t-label-s muted">{{ tr('Verify: ', 'যাচাই: ') + rx.code }}</span>
    <span v-if="print" class="t-label-s muted health-paper-foot">{{ tr('Issued on Lifolo. Scan the QR to check it is genuine.', 'Lifolo-তে দেওয়া প্রেসক্রিপশন। আসল কি না দেখতে QR স্ক্যান করুন।') }}</span>
  </article>
</template>
