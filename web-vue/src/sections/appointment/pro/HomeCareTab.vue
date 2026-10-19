<script setup lang="ts">
// বাসায় সেবা: চালু কি না, রিপোর্টের পর বন্ধ, অ্যাপ থেকে ফোনের হিসাব
import { RouterLink } from 'vue-router';
import { Button, ErrorBox, Icon, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import type { HomeStats } from '../model';
import { ratingValue } from '../shared';
import StatTile from '../StatTile.vue';
import MiniBars from '../MiniBars.vue';

defineProps<{ onSetup: () => void }>();
const q = useCall<HomeStats>('home_stats');
</script>

<template>
  <SkeletonCards v-if="q.isLoading" :n="2" :h="120" />
  <ErrorBox v-else-if="q.error || !q.data" :error="q.error ? errorText(q.error) : ''" :on-retry="() => q.refetch()" />
  <div v-else-if="q.data.blocked" class="card danger col">
    <span class="t-title-m">{{ tr('Your home care', 'আপনার বাসায় সেবা') }}</span>
    <p class="t-body-m">{{ tr('Turned off by the Lifolo team after a report. You can appeal.', 'রিপোর্টের পর Lifolo টিম বন্ধ করেছে। আপিল করতে পারেন।') }}</p>
    <RouterLink class="btn outline has-icon" to="/help/new?category=appointment" :style="{ alignSelf: 'flex-start' }"><Icon name="help" />{{ tr('Appeal / contact us', 'আপিল করুন / আমাদের লিখুন') }}</RouterLink>
  </div>
  <div v-else-if="!q.data.enabled" class="card tonal col">
    <span class="t-title-m">{{ tr('Your home care', 'আপনার বাসায় সেবা') }}</span>
    <p class="t-body-m">{{ tr('Do you visit patients at home? Turn it on so patients nearby can call you.', 'রোগীর বাসায় যান? চালু করুন, কাছের রোগীরা সরাসরি ফোন করবেন।') }}</p>
    <Button variant="filled" icon="home_health" :style="{ alignSelf: 'flex-start' }" @click="onSetup()">{{ tr('Turn on home care', 'বাসায় সেবা চালু করুন') }}</Button>
  </div>
  <div v-else class="col gap-16">
    <div class="row wrap">
      <span class="t-body-m grow">{{ tr('You are in the list — patients call you directly.', 'আপনি তালিকায় আছেন — রোগীরা সরাসরি ফোন করবেন।') }}</span>
      <Button variant="outline" icon="edit" @click="onSetup()">{{ tr('Change settings', 'সেটিং বদলান') }}</Button>
    </div>
    <div class="stats">
      <StatTile :value="num(q.data.calls?.[7] ?? 0)" :label="tr('Calls in 7 days', '৭ দিনে ফোন')" />
      <StatTile :value="num(q.data.calls?.[30] ?? 0)" :label="tr('Calls in 30 days', '৩০ দিনে ফোন')" />
      <StatTile :value="num(q.data.callers ?? 0)" :label="tr('Different callers', 'আলাদা মানুষ')" />
      <StatTile :value="ratingValue(q.data)" :label="tr('Rating', 'রেটিং')" />
      <StatTile v-if="q.data.reports > 0" :value="num(q.data.reports)" :label="tr('Reports', 'রিপোর্ট')" class="appt-stat-hot" />
    </div>
    <div class="ws-grid side">
      <section class="card col">
        <h2 class="t-title-s">{{ tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন') }}</h2>
        <MiniBars :values="q.data.daily ?? []" />
      </section>
      <section class="card col">
        <h2 class="t-title-s">{{ tr('Recent ratings', 'সাম্প্রতিক রেটিং') }}</h2>
        <template v-if="q.data.comments?.length">
          <div v-for="(c, i) in q.data.comments" :key="i" class="col gap-2 appt-comment">
            <span class="appt-stars-txt" :aria-label="num(c.rating)">{{ '★'.repeat(Math.max(0, Math.min(5, c.rating))) }}</span>
            <span v-if="c.comment" class="t-body-m">{{ c.comment }}</span>
          </div>
        </template>
        <span v-else class="t-body-m muted">—</span>
      </section>
    </div>
  </div>
</template>
