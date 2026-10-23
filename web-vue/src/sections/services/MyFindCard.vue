<script setup lang="ts">
import { Button, IconButton, PhoneLine } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatKm, timeAgo } from '@/lib/format';
import { recordCall } from './data';
import { drugTitle, needText, replyText, type MyFind } from './medfind';

defineProps<{ f: MyFind; guest: boolean }>();
const emit = defineEmits<{ report: [shopId: string]; close: [] }>();
</script>

<template>
  <div class="card col gap-8">
    <span class="t-title-s ellipsis">{{ drugTitle(f.drug) }}</span>
    <span v-if="f.qty" class="t-body-m medfind-need" data-testid="find-need">{{ needText(f.qty, f.unit) }}</span>
    <span class="t-body-s muted">{{ [f.area, timeAgo(f.createdAt), tr(`asked ${f.notified} shops`, `${num(f.notified)}টা দোকানে জিজ্ঞেস`), f.open ? tr('open', 'খোলা') : tr('closed', 'বন্ধ')].filter(Boolean).join(' · ') }}</span>
    <span v-if="!f.have.length" class="t-body-s muted">
      {{ f.open ? tr('No pharmacy has said yes yet. You\'ll get a notification.', 'এখনো কোনো দোকান "আছে" বলেনি। বললে নোটিফিকেশন পাবেন।') : tr('No pharmacy said it has this.', 'কোনো দোকান "আছে" বলেনি।') }}
    </span>
    <div v-for="sh in f.have" :key="sh.id" class="col gap-6 services-shop">
      <hr class="divider" />
      <div class="row top">
        <div class="col gap-2 grow">
          <span class="t-title-s">{{ sh.name }}</span>
          <span :class="['t-body-m', 'semi', sh.full ? 'c-primary' : 'medfind-reply-part']" data-testid="find-reply">{{ replyText(f, sh) }}</span>
          <span class="t-body-s muted">{{ [sh.km != null ? formatKm(sh.km) : null, sh.open24 ? tr('24 hours', '২৪ ঘণ্টা') : sh.openNow ? tr('open now', 'এখন খোলা') : tr('closed now', 'এখন বন্ধ')].filter(Boolean).join(' · ') }}</span>
          <span v-if="sh.address" class="t-body-s muted">{{ sh.address }}</span>
        </div>
        <IconButton small icon="flag" :label="tr('Report', 'রিপোর্ট')" @click="emit('report', sh.id)" />
      </div>
      <PhoneLine :phone="sh.phone" :on-call="() => recordCall('pharmacy', sh.id, guest)" />
    </div>
    <div v-if="f.open">
      <Button variant="text" @click="emit('close')">{{ tr('Found it — stop asking', 'পেয়ে গেছি — খোঁজ বন্ধ করুন') }}</Button>
    </div>
  </div>
</template>
