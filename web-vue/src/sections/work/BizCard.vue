<script setup lang="ts">
import { useSlots } from 'vue';
import { RouterLink } from 'vue-router';
import { Icon, slotHasContent } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { businessIcon, businessName, callsLine, type CallSummary, type Kind } from './shared';

defineProps<{ kind?: Kind; icon?: string; title?: string; sub: string; calls?: CallSummary | null; to: string; note?: string | null }>();
const slots = useSlots();
</script>

<template>
  <article class="card work-biz">
    <RouterLink :to="to" class="work-biz-head">
      <span class="work-ic"><Icon :name="icon ?? businessIcon(kind!)" /></span>
      <span class="col gap-2 grow">
        <span class="t-title-m">{{ title ?? businessName(kind!) }}</span>
        <span class="t-body-s muted clamp2">{{ sub }}</span>
      </span>
      <Icon name="chevron_right" class="work-chev" />
    </RouterLink>
    <div v-if="slotHasContent(slots.status) || calls !== undefined || note" class="col gap-6">
      <slot name="status" />
      <span v-if="note" class="t-body-s">{{ note }}</span>
      <span v-if="calls !== undefined" class="icon-line"><Icon name="call" /><span>{{ tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + callsLine(calls) }}</span></span>
    </div>
    <slot />
    <div v-if="slotHasContent(slots.actions)" class="row wrap gap-8 work-biz-actions"><slot name="actions" /></div>
  </article>
</template>
