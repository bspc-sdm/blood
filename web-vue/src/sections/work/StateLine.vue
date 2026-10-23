<script setup lang="ts">
// এখনকার অবস্থা শুধু দেখানো (খোলা / বিরতি / বন্ধ; খালি আছি / ট্রিপে আছি; আজ সিরিয়াল চলছে / আজ বন্ধ) আর "বদলান" → /work —
// বদলানো এক জায়গাতেই, "আমার কাজ"-এর অবস্থার বোতামে (আমার কাজ CONTRACT §১.২, §২ অ্যাম্বুলেন্স)।
import { RouterLink } from 'vue-router';
import { Icon, Tag } from '@/ui/kit';
import { tr } from '@/lib/i18n';

defineProps<{ label: string; tone?: 'good' | 'warn' | 'error' | null; name?: string }>();
</script>

<template>
  <div class="row wrap gap-8 pv-state" data-testid="state-line">
    <span v-if="name" class="t-label-l muted pv-state-name">{{ name }}</span>
    <span v-if="tone === 'warn'" class="td-note warn" data-testid="queue-state"><Icon name="schedule" />{{ label }}</span>
    <Tag v-else :tone="tone === 'good' ? 'good' : tone === 'error' ? 'error' : undefined" data-testid="queue-state">{{ label }}</Tag>
    <RouterLink to="/work" class="btn text small has-icon pv-state-change" data-testid="state-change"><Icon name="edit" />{{ tr('Edit', 'বদলান') }}</RouterLink>
  </div>
</template>
