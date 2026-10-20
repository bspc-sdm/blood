<script setup lang="ts">
// একটা তালিকার পুরো হিসাব (ডায়ালগে): ফোন, চার্ট, রেটিং, মন্তব্য, রিপোর্ট
import { Dialog } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import type { ListingStats } from './shared';
import Stat from './Stat.vue';
import MiniBars from './MiniBars.vue';

defineProps<{ title: string; st: ListingStats }>();
const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <Dialog :title="title" size="wide" @close="emit('close')">
    <div class="stats">
      <Stat :value="num(st.calls[7])" :label="tr('Calls in 7 days', '৭ দিনে ফোন')" />
      <Stat :value="num(st.calls[30])" :label="tr('Calls in 30 days', '৩০ দিনে ফোন')" />
      <Stat :value="num(st.callers)" :label="tr('Different callers', 'আলাদা মানুষ')" />
      <Stat v-if="st.ratingCount != null" :value="st.ratingCount ? num(String(st.rating)) : '—'" :label="tr('Rating', 'রেটিং') + (st.ratingCount ? ` (${num(st.ratingCount)})` : '')" />
    </div>
    <p class="t-title-s">{{ tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন') }}</p>
    <MiniBars :values="st.daily" />
    <p class="t-body-s muted">{{ tr(`${st.callers} different people called from the app in total.`, `মোট ${num(st.callers)} জন আলাদা মানুষ অ্যাপ থেকে ফোন করেছেন।`) }}</p>
    <p v-if="st.reports > 0" class="t-body-m c-error">{{ tr(`${st.reports} report(s)`, `রিপোর্ট ${num(st.reports)}টি`) }}</p>
    <div v-if="st.comments && st.comments.length" class="col gap-6">
      <p class="t-title-s">{{ tr('Recent ratings', 'সাম্প্রতিক রেটিং') }}</p>
      <p v-for="(c, i) in st.comments" :key="i" class="t-body-s"><span class="work-stars">{{ '★'.repeat(Math.max(0, Math.min(5, c.rating))) }}</span>{{ c.comment ? '  ' + c.comment : '' }}</p>
    </div>
  </Dialog>
</template>
