<script setup lang="ts">
// আজকের হিসাব (pay_summary): আজ পেয়েছেন (নিশ্চিত), মেলানোর অপেক্ষায়, এখনো পাঠাননি
import { ErrorText } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num } from '@/lib/i18n';
import { useQ } from '@/lib/query';
import { money } from '../../invoices/model';
import * as api from '../api';
import { sseLive } from '../live';
import type { BizKind, PaySummary } from '../model';
import { T } from '../text';

const props = defineProps<{ kind: BizKind; bizId: string }>();
const q = useQ<PaySummary>(() => ({
  queryKey: ['pay_summary', props.kind, props.bizId],
  queryFn: () => api.summary(props.kind, props.bizId),
  refetchInterval: () => (sseLive.value ? false : 60_000),
}));
</script>

<template>
  <section class="card col gap-8" data-testid="pay-summary">
    <h2 class="t-title-m">{{ T.today() }}</h2>
    <ErrorText v-if="q.isError" :text="errorText(q.error)" />
    <div class="pay-sum">
      <div>
        <span class="t-label-m muted">{{ T.collectedToday() }}</span>
        <b class="num" data-testid="sum-collected">{{ q.data ? money(q.data.collected) : '—' }}</b>
        <span v-if="q.data" class="t-body-s muted">{{ T.confirmedCount(q.data.confirmedCount) }}</span>
      </div>
      <div>
        <span class="t-label-m muted">{{ T.pendingClaims() }}</span>
        <b class="num" data-testid="sum-pending">{{ q.data ? num(q.data.pending) : '—' }}</b>
      </div>
      <div>
        <span class="t-label-m muted">{{ T.openRequests() }}</span>
        <b class="num" data-testid="sum-open">{{ q.data ? num(q.data.open) : '—' }}</b>
      </div>
    </div>
  </section>
</template>
