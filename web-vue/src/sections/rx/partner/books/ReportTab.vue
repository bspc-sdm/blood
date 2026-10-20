<script setup lang="ts">
// ফার্মেসি — "হিসাব": আজ / ৭ দিন / ৩০ দিন / এই মাস — বিক্রি, ছাড়, কেনা দাম, লাভ, মার্জিন, নষ্টে ক্ষতি, নিট লাভ;
// দিনের বিক্রি আর লাভের ছোট বার চার্ট; বেশি বিক্রি/লাভ, কেনা দামের নিচে বিক্রি; স্টকের দাম, কম স্টক, মেয়াদ, বিক্রি নেই।
import { ref } from 'vue';
import { ErrorBox, Segmented, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import Panel from '../Panel.vue';
import { expiryText, money, periods, qtyText, unitText, type Period, type RankRow, type Report, type ShopItem } from './model';
import ReportTotals from './ReportTotals.vue';
import DailyChart from './DailyChart.vue';
import RankList from './RankList.vue';
import ReportItems from './ReportItems.vue';

const period = ref<Period>('7d');
const q = useCall<Report>('books_report', () => ({ period: period.value }));
const qtyUnit = (i: ShopItem) => `${qtyText(i.qty)}${i.unit ? ' ' + unitText(i.unit) : ''}`;
const byNet = (x: RankRow) => money(x.net);
const byProfit = (x: RankRow) => money(x.profit);
const byLoss = (x: RankRow) => '−' + money(x.loss);
const lossSub = (x: RankRow) => tr(`${x.lossQty} sold`, `${qtyText(x.lossQty)}টা বিক্রি`);
const reorderSub = (i: ShopItem) => tr('reorder at ', 'আনবেন ') + qtyText(i.lowAt);
const expV = (i: ShopItem) => expiryText(i.expiry);
const expSub = (i: ShopItem) => (i.expDays != null && i.expDays < 0 ? tr('expired', 'মেয়াদ শেষ') : tr(`${i.expDays} days`, `${num(i.expDays ?? 0)} দিন`));
const costSub = (i: ShopItem) => (i.cost != null && i.qty != null ? money(Math.round(i.qty * i.cost)) : '');
</script>

<template>
  <div class="col gap-16">
    <div class="row wrap gap-8">
      <Segmented v-model="period" :label="tr('Period', 'সময়')" :options="periods()" />
    </div>
    <SkeletonCards v-if="q.isLoading" :n="2" :h="160" />
    <section v-else-if="q.error || !q.data" class="card"><ErrorBox :error="q.error ? errorText(q.error) : ''" :on-retry="() => q.refetch()" /></section>
    <template v-else>
      <ReportTotals :r="q.data" />
      <Panel :title="tr('Daily sales & profit', 'দিনের বিক্রি ও লাভ')" icon="history">
        <DailyChart :r="q.data" />
      </Panel>
      <div class="rxp-two">
        <Panel :title="tr('Top sellers', 'বেশি বিক্রি')" icon="payments"><RankList :rows="q.data.topSales" :v="byNet" /></Panel>
        <Panel :title="tr('Most profit', 'বেশি লাভ')" icon="star"><RankList :rows="q.data.topProfit" :v="byProfit" /></Panel>
      </div>
      <Panel v-if="q.data.belowCost.length" :title="tr('Sold below cost', 'কেনা দামের নিচে বিক্রি')" icon="warning" cls="bk-warn">
        <RankList :rows="q.data.belowCost" :v="byLoss" :sub="lossSub" />
      </Panel>
      <Panel :title="tr('Stock value', 'স্টকের দাম')" icon="list_alt">
        <div class="stats">
          <div class="stat"><span class="l">{{ tr('At cost', 'কেনা দামে') }}</span><span class="v">{{ money(q.data.stock.atCost) }}</span></div>
          <div class="stat"><span class="l">{{ tr('At sale price', 'বিক্রি দামে') }}</span><span class="v">{{ money(q.data.stock.atPrice) }}</span></div>
        </div>
        <p v-if="q.data.stock.noCost || q.data.stock.noPrice || q.data.stock.counted < q.data.stock.items" class="t-body-s muted">
          {{
            [
              q.data.stock.counted < q.data.stock.items ? tr(`${q.data.stock.items - q.data.stock.counted} without qty`, `${num(q.data.stock.items - q.data.stock.counted)}টার পরিমাণ নেই`) : '',
              q.data.stock.noCost ? tr(`${q.data.stock.noCost} without cost`, `${num(q.data.stock.noCost)}টার কেনা দাম নেই`) : '',
              q.data.stock.noPrice ? tr(`${q.data.stock.noPrice} without price`, `${num(q.data.stock.noPrice)}টার বিক্রি দাম নেই`) : '',
            ].filter(Boolean).join(' · ')
          }}
        </p>
      </Panel>
      <div class="rxp-two">
        <Panel :title="tr('Low stock', 'কম স্টক')" icon="warning" :count="num(q.data.low.length)">
          <ReportItems :list="q.data.low" :v="qtyUnit" :sub="reorderSub" />
        </Panel>
        <Panel :title="tr('Expiring in 60 days', '৬০ দিনের মধ্যে মেয়াদ শেষ')" icon="schedule" :count="num(q.data.expiring.length)">
          <ReportItems :list="q.data.expiring" :v="expV" :sub="expSub" bad />
        </Panel>
      </div>
      <Panel :title="tr('No sale in 30 days', '৩০ দিনে বিক্রি নেই')" icon="info" :count="num(q.data.noSale.length)">
        <ReportItems :list="q.data.noSale" :v="qtyUnit" :sub="costSub" />
      </Panel>
    </template>
  </div>
</template>
