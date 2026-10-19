<script setup lang="ts">
import { cx } from '@/ui/composables';
import { tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import type { TicketMessage } from './util';

defineProps<{ m: TicketMessage }>();
</script>

<template>
  <div :class="cx('acct-bubble', m.from === 'admin' && 'admin')">
    <p class="t-body-m pre">{{ m.text }}</p>
    <span class="t-label-s muted">{{ (m.from === 'admin' ? tr('Lifolo team', 'Lifolo টিম') : tr('You', 'আপনি')) + ', ' + timeAgo(m.at) }}</span>
  </div>
</template>
