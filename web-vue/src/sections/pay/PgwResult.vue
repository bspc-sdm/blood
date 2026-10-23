<script setup lang="ts">
// bKash গেটওয়ের ফল (/pay/result/:id) — শুধু Lifolo-র নিজের আয় (pay_bkash_pgw), কখনো অন্য কারো টাকা নয়।
// নিরাপত্তা পর্যালোচনা P7: ঠিকানার status/অন্য কিছু বিশ্বাস করা হয় না — শুধু id, অবস্থা সার্ভার থেকে (pgw_status {id}, নিজেরটা)।
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { RouterLink } from 'vue-router';
import { EmptyState, ErrorBox, Icon, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { comingSoon } from '@/lib/features';
import { useCall } from '@/lib/query';
import { usePageTitle } from '@/shell/chrome';
import { money } from '../invoices/model';
import { pgwOn } from './features';
import { T } from './text';

usePageTitle(() => T.pgwTitle());
const route = useRoute();
const id = computed(() => String(route.params.id ?? ''));
const q = useCall<{ status?: string; amount?: number; purpose?: string; trxId?: string }>('pgw_status', () => ({ id: id.value }), () => ({ enabled: pgwOn() && !!id.value, retry: false }));
// completed = সফল; created/pending = এখনো দেখা হচ্ছে (সার্ভার bKash-কে জিজ্ঞেস করে); বাকি সব ব্যর্থ/বাতিল
const st = computed(() => q.data?.status ?? '');
const ok = computed(() => st.value === 'completed');
const waiting = computed(() => st.value === 'created' || st.value === 'pending');
</script>

<template>
  <div class="pay-result" data-testid="pgw-result" :data-status="st || 'none'">
    <EmptyState v-if="!pgwOn()" icon="payments" :title="comingSoon()" data-testid="feature-off" />
    <SkeletonCards v-else-if="q.isPending || waiting" :n="1" :h="160" />
    <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
    <div v-else class="card col gap-12">
      <Icon :name="ok ? 'check_circle' : 'warning'" :size="48" />
      <h1 class="t-headline">{{ ok ? T.pgwOk() : st === 'cancelled' ? T.pgwCancel() : T.pgwFail() }}</h1>
      <p v-if="ok && q.data?.amount" class="pay-amount num">{{ money(q.data.amount) }}</p>
      <p v-if="ok && q.data?.trxId" class="t-body-m">TrxID: <span class="pay-trx">{{ q.data.trxId }}</span></p>
      <p v-if="!ok" class="t-body-m muted">{{ T.pgwFailText() }}</p>
      <RouterLink to="/" class="btn tonal">{{ T.pgwHome() }}</RouterLink>
    </div>
  </div>
</template>
