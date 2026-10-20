<script setup lang="ts">
// সেটআপের ভেতরে: রিপোর্টের পর বন্ধ হলে কারণ আর আপিল, চালু থাকলে ফোনের হিসাব
import { RouterLink } from 'vue-router';
import { Icon } from '@/ui/kit';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import type { HomeStats } from '../model';

const q = useCall<HomeStats>('home_stats');
</script>

<template>
  <template v-if="q.data && (q.data.blocked || q.data.enabled)">
    <div v-if="q.data.blocked" class="card danger col gap-6">
      <span class="t-body-m">{{ tr('Turned off by the Lifolo team after a report. You can appeal.', 'রিপোর্টের পর Lifolo টিম বন্ধ করেছে। আপিল করতে পারেন।') }}</span>
      <RouterLink class="btn outline small has-icon" to="/help/new?category=appointment" :style="{ alignSelf: 'flex-start' }"><Icon name="help" />{{ tr('Appeal / contact us', 'আপিল করুন / আমাদের লিখুন') }}</RouterLink>
    </div>
    <div v-else class="card tonal col gap-2">
      <span class="t-body-m">{{ tr('You are in the list — patients call you directly.', 'আপনি তালিকায় আছেন — রোগীরা সরাসরি ফোন করবেন।') }}</span>
      <span class="t-body-s">
        {{ tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + tr(`${q.data.calls?.[7] ?? 0} in 7 days · ${q.data.calls?.[30] ?? 0} in 30 days`, `৭ দিনে ${num(q.data.calls?.[7] ?? 0)} · ৩০ দিনে ${num(q.data.calls?.[30] ?? 0)}`) }}
      </span>
    </div>
  </template>
</template>
