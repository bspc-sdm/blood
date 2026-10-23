<script setup lang="ts">
// ব্যবসার পেমেন্টের তালিকা (pay_list_biz): inbox হলে শুধু রোগী জানানো (claimed) — মেলানোর অপেক্ষায়; নইলে অবস্থা আর সময় দিয়ে ছাঁকা।
// SSE pay_* এলে নিজে নতুন করে আনে (live.ts); SSE না চললে খোলা অবস্থায় ৩০ সেকেন্ড পরপর।
import { computed, ref } from 'vue';
import { Button, ChoiceChips, EmptyState, ErrorBox, Segmented, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useInfiniteQ } from '@/lib/query';
import { periods, type Invoice, type Period } from '../../invoices/model';
import ReceiptDialog from '../../invoices/ReceiptDialog.vue';
import InvoiceByIdDialog from '../../invoices/InvoiceByIdDialog.vue';
import * as api from '../api';
import { sseLive, usePayLive } from '../live';
import type { BizKind, PayPage, PayRequest, PayStatus } from '../model';
import { T, statusTextBiz } from '../text';
import BizPayRow from './BizPayRow.vue';

const props = defineProps<{ kind: BizKind; bizId: string; owner: boolean; extra: Record<string, unknown>; inbox?: boolean }>();
usePayLive();
const status = ref<PayStatus | ''>('');
const period = ref<Period>('7d');
const STATUS_OPTS: (PayStatus | '')[] = ['', 'claimed', 'open', 'confirmed', 'rejected', 'refunded', 'expired'];
const q = useInfiniteQ<PayPage>(() => ({
  queryKey: ['pay_list_biz', props.kind, props.bizId, props.inbox ? 'inbox' : status.value, props.inbox ? '' : period.value],
  queryFn: ({ pageParam }: { pageParam: string | null }) =>
    api.listBiz(props.kind, props.bizId, props.inbox ? { status: 'claimed', cursor: pageParam } : { status: status.value, period: period.value, cursor: pageParam }),
  initialPageParam: null,
  getNextPageParam: (last: PayPage) => last.next ?? undefined,
  enabled: !!props.bizId,
  refetchInterval: () => (sseLive.value ? false : 30_000),
}));
const items = computed<PayRequest[]>(() => ((q.data as any)?.pages ?? []).flatMap((p: PayPage) => p.items).filter((r: PayRequest) => !props.inbox || r.status === 'claimed'));
const receipt = ref<Invoice | null>(null);
const receiptId = ref<string | null>(null);
</script>

<template>
  <div class="col gap-12" :data-testid="inbox ? 'pay-inbox' : 'pay-list'">
    <template v-if="!inbox">
      <ChoiceChips v-model="status" :options="STATUS_OPTS.map((s) => ({ value: s, label: s ? statusTextBiz(s) : T.filterAll() }))" data-testid="pay-status-filter" />
      <Segmented v-model="period" :options="periods()" />
    </template>
    <SkeletonCards v-if="q.isPending" :n="3" :h="72" />
    <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
    <EmptyState v-else-if="!items.length" icon="payments" :title="inbox ? T.inboxEmpty() : T.noneHere()" :text="inbox ? T.inboxEmptyText() : undefined" />
    <ul v-else class="pay-list">
      <BizPayRow
        v-for="r in items"
        :key="r.id"
        :r="r"
        :owner="owner"
        :extra="extra"
        @changed="q.refetch()"
        @receipt="(inv) => (receipt = inv)"
        @receipt-id="(id) => (receiptId = id)"
      />
    </ul>
    <Button v-if="q.hasNextPage" variant="text" :busy="q.isFetchingNextPage" @click="q.fetchNextPage()">{{ T.loadMore() }}</Button>
    <ReceiptDialog v-if="receipt" :inv="receipt" @close="receipt = null" />
    <InvoiceByIdDialog v-if="receiptId" :id="receiptId" @close="receiptId = null" />
  </div>
</template>
