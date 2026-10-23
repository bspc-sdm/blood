<script setup lang="ts">
// "দিনের শেষ" (নিরাপত্তা পর্যালোচনা I2; invoice_day_summary, শুধু মালিক) — রাত ৯:৩০-এর পুশের বিস্তারিত:
// মোট রসিদ আর টাকা, কে কতটা (byIssuer), বাতিল (কারণসহ), ১০%-এর বেশি ছাড়, জিনিসের দামের নিচে বিক্রি আর নষ্ট/মেয়াদ শেষে ক্ষতি (ফার্মেসি)।
// দিন বাছা যায় (আজ, গতকাল …)।
import { computed, ref } from 'vue';
import { EmptyState, ErrorBox, Icon, SkeletonCards, Segmented } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatTime } from '@/lib/format';
import { T } from './text';
import { invNo, money, todayStr, voidReasonText, type InvKind } from './model';

interface DaySum {
  day: string; receipts: number; total: number;
  byIssuer: { uid: string; name: string; receipts: number; total: number; paid: number; voids: number }[];
  voids: { id: string; no: string; total: number; reason: string; note?: string; at: number; issuedAt: number; by: string; byName: string }[];
  bigDiscounts: { id: string; no: string; total: number; discount: number; pct: number }[];
  belowPrice?: { id: string; no: string; name: string; qty: number; price: number; itemPrice: number }[];
  writeOffs: { count: number; loss: number; items: { name: string; qty: number; loss: number; reason: string }[] } | null;
}
const props = defineProps<{ kind: InvKind; bizId: string }>();
const DAY = 24 * 3600 * 1000;
const which = ref<0 | 1>(0);
const day = computed(() => todayStr(Date.now() - which.value * DAY));
const q = useCall<DaySum>('invoice_day_summary', () => ({ kind: props.kind, bizId: props.bizId, day: day.value }), () => ({ enabled: !!props.bizId }));
const d = computed(() => q.data ?? null);
const voidsTotal = computed(() => (d.value?.voids ?? []).reduce((a, v) => a + v.total, 0));
</script>

<template>
  <section class="card col gap-12 inv-day" data-testid="day-summary">
    <div class="row wrap gap-8">
      <Icon name="history" :size="20" />
      <h3 class="t-title-m grow">{{ T.dayEnd() }}</h3>
      <Segmented v-model="which" :options="[{ value: 0, label: tr('Today', 'আজ') }, { value: 1, label: T.yesterday() }]" />
    </div>
    <SkeletonCards v-if="q.isPending" :n="1" :h="120" />
    <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
    <EmptyState v-else-if="!d || (!d.receipts && !d.voids.length && !d.writeOffs?.count)" icon="list_alt" :title="T.noReceipts()" />
    <template v-else>
      <dl class="kv num">
        <dt>{{ T.receipts() }}</dt><dd>{{ num(d.receipts) }}</dd>
        <dt>{{ tr('Total', 'মোট') }}</dt><dd data-testid="day-total">{{ money(d.total) }}</dd>
        <dt>{{ T.voided() }}</dt><dd data-testid="day-voids">{{ num(d.voids.length) }}{{ d.voids.length ? ' · ' + money(voidsTotal) : '' }}</dd>
        <dt>{{ T.bigDiscounts() }}</dt><dd>{{ num(d.bigDiscounts.length) }}</dd>
        <template v-if="d.belowPrice"><dt>{{ T.belowPrice() }}</dt><dd>{{ num(d.belowPrice.length) }}</dd></template>
        <template v-if="d.writeOffs"><dt>{{ T.writeOffs() }}</dt><dd>{{ money(d.writeOffs.loss) }}{{ d.writeOffs.count ? ' · ' + num(d.writeOffs.count) : '' }}</dd></template>
      </dl>
      <div v-if="d.byIssuer.length > 1" class="col gap-4" data-testid="day-by-issuer">
        <span class="t-label-l">{{ T.byPerson() }}</span>
        <span v-for="p in d.byIssuer" :key="p.uid" class="t-body-s">{{ p.name || '—' }} · {{ num(p.receipts) }} · {{ money(p.paid) }}{{ p.voids ? ' · ' + T.voided() + ' ' + num(p.voids) : '' }}</span>
      </div>
      <div v-if="d.voids.length" class="col gap-4">
        <span class="t-label-l">{{ T.voided() }}</span>
        <span v-for="v in d.voids" :key="v.id" class="t-body-s">{{ invNo(v.no) }} · {{ formatTime(v.at) }} · {{ money(v.total) }} — {{ voidReasonText({ kind, voidReason: v.reason, voidNote: v.note }) }}{{ v.byName ? ' · ' + v.byName : '' }}</span>
      </div>
      <div v-if="d.bigDiscounts.length" class="col gap-4">
        <span class="t-label-l">{{ T.bigDiscounts() }}</span>
        <span v-for="x in d.bigDiscounts" :key="x.id" class="t-body-s">{{ invNo(x.no) }} · {{ money(x.discount) }} ({{ num(Math.round(x.pct)) }}%) / {{ money(x.total) }}</span>
      </div>
      <div v-if="d.belowPrice?.length" class="col gap-4">
        <span class="t-label-l">{{ T.belowPrice() }}</span>
        <span v-for="(x, k) in d.belowPrice" :key="x.id + k" class="t-body-s">{{ invNo(x.no) }} · {{ x.name }} × {{ num(x.qty) }} · {{ money(x.price) }} &lt; {{ money(x.itemPrice) }}</span>
      </div>
      <div v-if="d.writeOffs?.items.length" class="col gap-4">
        <span class="t-label-l">{{ T.writeOffs() }}</span>
        <span v-for="(x, k) in d.writeOffs.items" :key="k" class="t-body-s">{{ x.name }} × {{ num(x.qty) }} · {{ money(x.loss) }}{{ x.reason ? ' — ' + x.reason : '' }}</span>
      </div>
    </template>
  </section>
</template>
