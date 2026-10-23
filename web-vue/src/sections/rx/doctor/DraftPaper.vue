<script setup lang="ts">
// দেওয়ার আগে কাগজ
import { num, tr } from '@/lib/i18n';
import { formatDate, todayUtcMidnight } from '@/lib/format';
import { medHowTo, medTitle } from '../../health/rx';
import type { RxMed } from './model';
import { T as PT, type RxKind } from '@/lib/practice';

withDefaults(defineProps<{
  /** lib/practice.ts: পরামর্শের শিরোনাম, সাইকিয়াট্রিস্টের কথা; headTag — নামের পাশে পদ্ধতি/পেশা */
  kind?: RxKind; headTag?: string | null;
  doctorName: string; degrees: string; chamber: string; patient: string; complaints: string; findings: string; diagnosis: string; meds: RxMed[];
  tests: string[]; testPlace: string; advice: string; follow: string;
}>(), { kind: 'full', headTag: null });
</script>

<template>
  <article class="health-paper rxd-draft" :aria-label="tr('Prescription', 'প্রেসক্রিপশন')">
    <div class="col gap-2">
      <h3 class="t-title-l">{{ doctorName }}</h3>
      <span v-if="headTag" class="t-body-m health-paper-h" data-testid="paper-system">{{ headTag }}</span>
      <span v-if="degrees" class="t-body-m">{{ degrees }}</span>
      <span v-if="chamber" class="t-body-s muted">{{ chamber }}</span>
    </div>
    <hr class="divider" />
    <div class="row wrap">
      <span class="t-title-s grow">{{ patient }}</span>
      <span class="t-body-s muted">{{ formatDate(todayUtcMidnight()) }}</span>
    </div>
    <template v-for="b in [
      { title: tr('Complaints', 'সমস্যা'), text: complaints },
      { title: tr('Findings', 'পরীক্ষায় পাওয়া'), text: findings },
      { title: kind === 'advice' ? PT.assessment() : tr('Diagnosis', 'রোগনির্ণয়'), text: diagnosis },
    ]" :key="b.title">
      <div v-if="b.text" class="col gap-2">
        <span class="t-label-l health-paper-h">{{ b.title }}</span>
        <p class="t-body-m pre">{{ b.text }}</p>
      </div>
    </template>
    <div v-if="meds.length" class="col">
      <span class="health-rx-sign" aria-hidden="true">℞</span>
      <ol class="col health-meds">
        <li v-for="(m, i) in meds" :key="i" class="row top">
          <span class="t-body-l health-med-n">{{ num(i + 1) }}.</span>
          <div class="col gap-2 grow">
            <span class="t-body-l semi">{{ medTitle(m) }}</span>
            <span class="t-body-m muted">{{ medHowTo(m) }}</span>
            <span v-if="m.note" class="t-body-s muted pre">{{ m.note }}</span>
          </div>
        </li>
      </ol>
    </div>
    <div v-if="tests.length" class="col gap-2">
      <span class="t-label-l health-paper-h">{{ tr('Tests', 'পরীক্ষা') }}</span>
      <p class="t-body-m pre">{{ tests.map((t) => '• ' + t).join('\n') }}</p>
    </div>
    <span v-if="tests.length && testPlace" class="t-body-s muted">{{ tr('Suggested place: ', 'করাতে পারেন: ') + testPlace + tr(' (or anywhere you like)', ' (চাইলে অন্য জায়গাতেও)') }}</span>
    <template v-for="b in [
      { title: kind === 'advice' ? PT.adviceExercise() : kind === 'notes' ? PT.sessionNotes() : tr('Advice', 'পরামর্শ'), text: advice },
      { title: tr('Come again', 'আবার দেখাবেন'), text: follow },
    ]" :key="b.title">
      <div v-if="b.text" class="col gap-2">
        <span class="t-label-l health-paper-h">{{ b.title }}</span>
        <p class="t-body-m pre">{{ b.text }}</p>
      </div>
    </template>
    <p v-if="kind === 'notes'" class="t-body-m semi">{{ PT.psychiatrist() }}</p>
  </article>
</template>
