<script setup lang="ts">
// ব্যবসার রসিদের তালিকা (invoice_list_biz): আজ / ৭ দিন / ৩০ দিন / এই মাস, নাম-ফোন-রসিদ নং দিয়ে খোঁজা,
// প্রতিটায় রসিদ দেখা/ছাপা, বাকি আদায়, বাতিল (কারণসহ)। উপরে আদায় আর বাকির যোগফল (বাতিল বাদে; সার্ভারের sum — পুরো সময়ের)।
// মালিক: "দিনের শেষ" (DaySummary) আর মাসের CSV। কোন রসিদ বাতিল করা যায় তা canVoidInv ঠিক করে (সহকারী: নিজের, ২ ঘণ্টার মধ্যে)।
// ডেস্কটপে কাউন্টারের পিনে "দিনের শেষ" আর CSV লুকানো।
import { computed, ref, watch } from 'vue';
import { Button, EmptyState, ErrorBox, Icon, PillTabs, Segmented, SkeletonCards, Tag } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useInfiniteQ } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDayOf, formatTime } from '@/lib/format';
import { T } from './text';
import { voidReasonText, maskPhone, methodText, methodsOf, money, parseInvoice, parsePage, periods, r2, type InvKind, type InvPage, type Invoice, type Period } from './model';
import ReceiptDialog from './ReceiptDialog.vue';
import PayDueDialog from './PayDueDialog.vue';
import VoidDialog from './VoidDialog.vue';
import DaySummary from './DaySummary.vue';
import ExportCsv from './ExportCsv.vue';
import { deskRole, isDesktop } from './offline';
import { offline } from '@/offline';

const props = withDefaults(
  defineProps<{ kind: InvKind; bizId: string; title?: string; canVoidInv?: (i: Invoice) => boolean; owner?: boolean; initialPeriod?: Period }>(),
  { canVoidInv: () => true, owner: true, initialPeriod: 'today', title: undefined },
);
const view = ref<'list' | 'day'>('list');
const ownerView = computed(() => props.owner && deskRole() !== 'counter');
const period = ref<Period>(props.initialPeriod);
const q = ref('');
const qq = ref('');
let t: ReturnType<typeof setTimeout> | undefined;
watch(q, (v) => {
  clearTimeout(t);
  t = setTimeout(() => (qq.value = v.trim()), 300);
});
// ডেস্কটপে এই কম্পিউটারের রসিদ (offline.invoices — অফলাইনেও চলে), নইলে সার্ভারের তালিকা
const desk = isDesktop();
const list = useInfiniteQ<InvPage>(() => ({
  queryKey: ['invoice_list_biz', props.kind, props.bizId, period.value, qq.value, desk ? 'desktop' : 'web'],
  queryFn: ({ pageParam }: { pageParam: string | null }) =>
    desk
      ? offline.invoices({ period: period.value, ...(qq.value ? { q: qq.value } : {}) }).then((xs) => ({ items: xs.filter((i) => i.kind === props.kind).map(parseInvoice), next: null, sum: null }))
      : call('invoice_list_biz', { kind: props.kind, bizId: props.bizId, period: period.value, ...(qq.value ? { q: qq.value } : {}), ...(pageParam ? { cursor: pageParam } : {}) }).then(parsePage),
  initialPageParam: null,
  getNextPageParam: (last: InvPage) => last.next ?? undefined,
  enabled: !!props.bizId,
}));
const items = computed<Invoice[]>(() => ((list.data as any)?.pages ?? []).flatMap((p: InvPage) => p.items));
const live = computed(() => items.value.filter((i) => i.status !== 'void'));
const serverSum = computed(() => ((list.data as any)?.pages?.[0] as InvPage | undefined)?.sum ?? null);
const sums = computed(() => serverSum.value ?? { paid: r2(live.value.reduce((a, i) => a + i.paid, 0)), due: r2(live.value.reduce((a, i) => a + i.due, 0)) });
const open = ref<Invoice | null>(null);
const paying = ref<Invoice | null>(null);
const voiding = ref<Invoice | null>(null);
const whenOf = (i: Invoice) => (period.value === 'today' ? formatTime(i.issuedAt) : formatDayOf(i.issuedAt) + ', ' + formatTime(i.issuedAt));
const whoOf = (i: Invoice) => [i.customer?.name, i.customer?.phone ? num(maskPhone(i.customer.phone)) : ''].filter(Boolean).join(' · ');
function replaced(next: Invoice | null) {
  if (next && open.value?.id === next.id) open.value = next;
  paying.value = null;
  voiding.value = null;
}
</script>

<template>
  <section class="card rxp-panel col gap-12 inv-biz" data-testid="biz-invoices">
    <div class="rxp-panel-head">
      <span class="rxp-panel-ic"><Icon name="list_alt" /></span>
      <h2 class="t-title-m grow">{{ title ?? T.receipts() }}</h2>
      <span v-if="items.length" class="rxp-count num">{{ num(items.length) }}</span>
    </div>
    <div v-if="ownerView" class="rxp-sub-pills">
      <PillTabs
        secondary
        :label="title ?? T.receipts()"
        :value="view"
        :tabs="[{ key: 'list', label: T.receipts(), icon: 'list_alt' }, { key: 'day', label: T.dayEnd(), icon: 'history' }]"
        @change="(k) => (view = k as 'list' | 'day')"
      />
    </div>
    <DaySummary v-if="view === 'day' && ownerView" :kind="kind" :biz-id="bizId" />
    <template v-else>
    <div class="row wrap gap-8">
      <Segmented v-model="period" :options="periods()" />
      <div class="rxp-search grow inv-search">
        <Icon name="search" />
        <input class="input" :value="q" maxlength="40" :placeholder="T.searchReceipts()" :aria-label="T.searchReceipts()" @input="q = ($event.target as HTMLInputElement).value" />
      </div>
    </div>
    <div v-if="items.length" class="row wrap gap-8 inv-sums">
      <span class="t-body-m">{{ T.collectionTotal() }} <b class="num" data-testid="sum-paid">{{ money(sums.paid) }}</b></span>
      <span v-if="sums.due > 0" class="t-body-m c-error">{{ T.dueTotal() }} <b class="num" data-testid="sum-due">{{ money(sums.due) }}</b></span>
    </div>
    <SkeletonCards v-if="list.isPending" :n="3" :h="64" />
    <ErrorBox v-else-if="list.isError" :error="errorText(list.error)" :on-retry="() => list.refetch()" />
    <EmptyState v-else-if="!items.length" icon="list_alt" :title="T.noReceipts()" />
    <ul v-else class="bk-list inv-list">
      <li v-for="i in items" :key="i.id" :class="i.status === 'void' && 'inv-void-row'" data-testid="biz-invoice">
        <div class="row gap-8">
          <button type="button" class="inv-no num" @click="open = i">{{ num(i.no) }}</button>
          <span class="t-body-s muted grow">{{ whenOf(i) }}{{ whoOf(i) ? ' · ' + whoOf(i) : '' }}</span>
          <span class="num semi">{{ money(i.total) }}</span>
        </div>
        <div class="row wrap gap-6">
          <Tag v-if="i.status === 'void'" tone="error">{{ T.voided() }}</Tag>
          <Tag v-else-if="i.due > 0" tone="error">{{ T.due() }} {{ money(i.due) }}</Tag>
          <Tag v-else tone="good">{{ T.paid() }}</Tag>
          <span class="t-body-s muted grow">{{ [methodsOf(i).map(methodText).join(', '), i.lines.map((l) => l.name).slice(0, 3).join(', ')].filter(Boolean).join(' · ') }}</span>
          <Button small variant="text" @click="open = i">{{ T.seeReceipt() }}</Button>
          <Button v-if="i.status !== 'void' && i.due > 0" small variant="tonal" data-testid="row-collect" @click="paying = i">{{ T.collectDue() }}</Button>
          <Button v-if="i.status !== 'void' && canVoidInv(i)" small variant="danger-text" data-testid="row-void" @click="voiding = i">{{ T.voidIt() }}</Button>
        </div>
        <span v-if="i.status === 'void' && i.voidReason" class="t-body-s muted">{{ T.voidReason(voidReasonText(i)) }}</span>
      </li>
    </ul>
    <Button v-if="list.hasNextPage" variant="text" :busy="list.isFetchingNextPage" @click="list.fetchNextPage()">{{ T.loadMore() }}</Button>
    <span v-if="items.length" class="t-body-s muted">{{ T.count(items.length) + (items.length - live.length ? ' · ' + tr('void ', 'বাতিল ') + num(items.length - live.length) : '') }}</span>
    <ExportCsv v-if="ownerView" :kind="kind" :biz-id="bizId" />
    </template>

    <ReceiptDialog v-if="open && !paying && !voiding" :inv="open" @close="open = null" />
    <PayDueDialog v-if="paying" :inv="paying" @close="paying = null" @done="replaced" />
    <VoidDialog v-if="voiding" :inv="voiding" @close="voiding = null" @done="replaced" />
  </section>
</template>
