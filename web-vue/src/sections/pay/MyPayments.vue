<script setup lang="ts">
// রোগী: "আমার রসিদ ও খরচ"-এর পেমেন্ট ট্যাব (my_payments) — তালিকা আর একটা পেমেন্টের শিট (/invoices/payments/:id)।
// কেয়ারগিভার: উপরে কার (নিজের / রোগী) — my_payments আর পেমেন্টের কাজগুলোতে asUid যায় (lib/care.ts)।
// SSE pay_* এলে তালিকা আর খোলা পেমেন্ট নিজে নতুন হয় (live.ts)।
import { computed } from 'vue';
import { Button, EmptyState, ErrorBox, Icon, PillTabs, SkeletonCards, Tag, cx, type PillTab } from '@/ui/kit';
import ListDetail from '@/shell/ListDetail.vue';
import DetailPane from '@/shell/DetailPane.vue';
import { errorText } from '@/lib/api';
import { useInfiniteQ } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { formatDayOf } from '@/lib/format';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useNav } from '@/lib/nav';
import { money } from '../invoices/model';
import CareSwitch from '../invoices/CareSwitch.vue';
import * as api from './api';
import { sseLive, usePayLive } from './live';
import type { PayPage, PayRequest } from './model';
import { T, purposeIcon, purposeText, statusShort, statusTone } from './text';
import PayRequestSheet from './PayRequestSheet.vue';

const props = defineProps<{ id: string | null; tabs: PillTab[] }>();
const emit = defineEmits<{ tab: [k: string] }>();
const nav = useNav();
const s = useSession();
const lang = useLang();
const care = useCareMode();
usePayLive();
const q = useInfiniteQ<PayPage>(() => ({
  queryKey: ['my_payments', care.value?.uid ?? null, lang.value, s.uid],
  queryFn: ({ pageParam }: { pageParam: string | null }) => api.myPayments(pageParam),
  initialPageParam: null,
  getNextPageParam: (last: PayPage) => last.next ?? undefined,
  enabled: !s.guest,
  refetchInterval: () => (sseLive.value ? false : 60_000),
}));
const items = computed<PayRequest[]>(() => ((q.data as any)?.pages ?? []).flatMap((p: PayPage) => p.items));
const fromList = computed(() => (props.id ? items.value.find((i) => i.id === props.id) ?? null : null));
</script>

<template>
  <ListDetail :has-detail="!!(id && !s.guest)">
    <div class="list-inner" data-testid="my-payments">
      <div class="health-tabs">
        <PillTabs :label="T.payments()" value="payments" :tabs="tabs" @change="(k) => emit('tab', k)" />
      </div>
      <EmptyState v-if="s.guest" icon="payments" :title="T.myPayments()" :text="T.noPaymentsText()">
        <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
      </EmptyState>
      <template v-else>
        <CareSwitch />
        <h2 class="t-headline">{{ T.myPayments() }}</h2>
        <SkeletonCards v-if="q.isPending" :n="3" :h="88" />
        <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
        <EmptyState v-else-if="!items.length" icon="payments" :title="T.noPayments()" :text="T.noPaymentsText()" />
        <template v-else>
          <button
            v-for="r in items"
            :key="r.id"
            type="button"
            :class="cx('card col gap-4 inv-card pay-card', r.id === id && 'selected')"
            data-testid="my-payment"
            :data-status="r.status"
            @click="nav(`/invoices/payments/${r.id}`)"
          >
            <span class="row gap-8">
              <Icon :name="purposeIcon(r.purpose)" :size="20" />
              <span class="t-title-s grow ellipsis">{{ r.bizName || purposeText(r.purpose) }}</span>
              <span class="num semi">{{ money(r.amount) }}</span>
            </span>
            <span class="row wrap gap-6">
              <span class="t-body-s muted grow">{{ purposeText(r.purpose) }} · {{ formatDayOf(r.createdAt) }}</span>
              <Tag :tone="statusTone(r.status)">{{ statusShort(r.status, r.purpose) }}</Tag>
            </span>
          </button>
          <Button v-if="q.hasNextPage" variant="text" :busy="q.isFetchingNextPage" @click="q.fetchNextPage()">{{ T.loadMore() }}</Button>
        </template>
      </template>
    </div>
    <template #detail>
      <DetailPane v-if="id && !s.guest" :key="id" :title="fromList ? purposeText(fromList.purpose) : T.sheetTitle()" :on-back="() => nav('/invoices/payments')">
        <section class="card">
          <PayRequestSheet :id="id" :initial="fromList" />
        </section>
      </DetailPane>
    </template>
    <template v-if="!s.guest" #placeholder><EmptyState icon="payments" :title="T.pickPayment()" /></template>
  </ListDetail>
</template>
