<script setup lang="ts">
// /work/pharmacy — নিজের ফার্মেসি: অবস্থা, কয়েক দিন বন্ধ, হিসাব, বদল (অ্যাপের MyPharmacyPanel, ShopEditScreen)
// ২.৩০.০: "দোকান › তথ্য" (/work/pharmacy/shop) আর আলাদা ট্যাব "ওষুধ খোঁজা" (/work/pharmacy/finds — অ্যাপের PharmacyFindRequests)
import { computed, defineAsyncComponent, ref } from 'vue';
import { Button, ConfirmDialog, Dialog, ErrorText, Icon, Loading } from '@/ui/kit';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { formatDate, todayUtcMidnight } from '@/lib/format';
import { DAY_MS, DHAKA_OFFSET_MS } from '@/lib/rules';
import { useNav } from '@/lib/nav';
import { closedDaysText, hoursText, myBusinessTitle, pharmacyDocs, statusText, useAfterChange, useAreas, useFlash, type CallSummary, type Pharmacy } from './shared';
import BizPage from './BizPage.vue';
import WorkGate from './WorkGate.vue';
import WorkEmpty from './WorkEmpty.vue';
import Flash from './Flash.vue';
import Stat from './Stat.vue';
import MiniBars from './MiniBars.vue';
import StatusTag from './StatusTag.vue';
import StatusReason from './StatusReason.vue';
import AppealButton from './AppealButton.vue';
import DocsNeededNotice from './DocsNeededNotice.vue';
import PharmacyTabs from './PharmacyTabs.vue';
import DangerZone from './DangerZone.vue';

// দোকানের QR, অনলাইনে বিক্রি, চেয়েও পাননি — কাউন্টারের অংশের সাথে আলাদা ভাগে (rx/partner) আসে
const ShopPanels = defineAsyncComponent({ loader: () => import('../rx/partner/ShopPanels.vue'), loadingComponent: Loading });

const s = useSession();
const nav = useNav();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const ph = computed(() => s.work.pharmacy as Pharmacy | null);
const stats = useCall<CallSummary>('pharmacy_stats', {}, () => ({ enabled: ready.value && !!ph.value }));
const after = useAfterChange();
const [flash, setFlash] = useFlash();
const err = ref<string | null>(null);
const busy = ref(false);
const pausing = ref(false);
const del = ref(false);
const delErr = ref<string | null>(null);
useAreas();
const title = computed(() => myBusinessTitle('pharmacy'));

async function act(done: string, f: () => Promise<unknown>) {
  busy.value = true;
  err.value = null;
  setFlash(null);
  try {
    await f();
    await after();
    setFlash(done);
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function pause(days: number) {
  pausing.value = false;
  // বাংলাদেশ সময়ে শেষ দিনের রাত পর্যন্ত
  const until = todayUtcMidnight() + (days + 1) * DAY_MS - DHAKA_OFFSET_MS;
  act(tr('People will see it as closed until then', 'ততদিন তালিকায় "বন্ধ" দেখাবে'), () => call('pharmacy_pause', { until }));
}
async function remove() {
  busy.value = true;
  delErr.value = null;
  try {
    await call('pharmacy_delete');
    await after();
    del.value = false;
    setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
  } catch (e) {
    delErr.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
const place = computed(() => (ph.value ? [ph.value.address, areaLabel(ph.value.district, ph.value.upazilaId)].filter(Boolean).join(', ') : ''));
const hours = computed(() => (ph.value ? [hoursText(ph.value), closedDaysText(ph.value.closedDays)].filter(Boolean).join(' · ') : ''));
const terms = computed(() => {
  const p = ph.value!;
  return [
    (p.discountMin ?? 0) > 0 ? tr(`Discount over Tk ${p.discountMin}`, `৳${num(p.discountMin!)}-এর বেশি কিনলে ছাড়`) : '',
    (p.freeDeliveryOver ?? 0) > 0 ? tr(`free delivery over Tk ${p.freeDeliveryOver}`, `৳${num(p.freeDeliveryOver!)}-এর বেশিতে ডেলিভারি ফ্রি`) : '',
    p.discountNote ?? '',
  ]
    .filter(Boolean)
    .join(' · ');
});
const askDel = () => {
  delErr.value = null;
  del.value = true;
};
</script>

<template>
  <BizPage v-if="!ready" :title="title"><WorkGate /></BizPage>
  <BizPage v-else-if="!ph" :title="title">
    <Flash :msg="flash" />
    <WorkEmpty
      icon="storefront"
      :title="tr('Have a licensed pharmacy?', 'লাইসেন্সধারী ফার্মেসি আছে?')"
      :text="tr('Add it so people nearby can find it — especially at night — and call you directly. Free: no commission, the app sells nothing.', 'যোগ করুন, যাতে কাছের মানুষ (বিশেষ করে রাতে) খুঁজে পেয়ে সরাসরি ফোন করতে পারেন। বিনামূল্যে: কোনো কমিশন নেই, অ্যাপ কিছু বিক্রি করে না।')"
    >
      <Button icon="add" @click="nav('/work/pharmacy/new')">{{ tr('Add my pharmacy', 'আমার ফার্মেসি যোগ করুন') }}</Button>
    </WorkEmpty>
  </BizPage>
  <BizPage v-else :title="title">
    <template #actions><Button variant="tonal" icon="edit" @click="nav('/work/pharmacy/edit')">{{ tr('Edit details', 'তথ্য বদলান') }}</Button></template>
    <PharmacyTabs active="info" />
    <Flash :msg="flash" :error="err" />
    <DocsNeededNotice kind="pharmacy" :id="ph.id" :specs="pharmacyDocs()" :item="ph" @done="after" />

    <!-- "দোকান › তথ্য": পাশে তথ্য আর কয়েক দিন বন্ধ; মাঝে QR ও কোড (পোস্টার), অনলাইনে বিক্রি, চেয়েও পাননি, ফোনের হিসাব; একদম নিচে (লাল) মুছে ফেলা -->
    <div class="ws-grid side">
      <div class="col gap-16">
        <ShopPanels v-if="ph.status === 'approved'" />
        <section class="card col gap-8">
          <h3 class="t-title-m">{{ tr('Calls from the app', 'অ্যাপ থেকে ফোন') }}</h3>
          <div class="stats work-stats">
            <Stat icon="call" :value="stats.data ? num(stats.data.calls[7]) : '—'" :label="tr('Calls from the app · 7 days', 'অ্যাপ থেকে ফোন · ৭ দিনে')" />
            <Stat icon="call" :value="stats.data ? num(stats.data.calls[30]) : '—'" :label="tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')" />
            <Stat icon="call" :value="stats.data ? num(stats.data.calls[90]) : '—'" :label="tr('Calls from the app · 90 days', 'অ্যাপ থেকে ফোন · ৯০ দিনে')" />
            <Stat icon="group_round" :value="stats.data ? num(stats.data.callers) : '—'" :label="tr('Different callers', 'আলাদা মানুষ')" />
          </div>
          <template v-if="stats.data">
            <p class="t-body-m">{{ tr(`${stats.data.calls[7]} in 7 days · ${stats.data.calls[30]} in 30 days`, `৭ দিনে ${num(stats.data.calls[7])} · ৩০ দিনে ${num(stats.data.calls[30])}`) }}</p>
            <p class="t-title-s">{{ tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন') }}</p>
            <MiniBars :values="stats.data.daily" />
          </template>
          <ErrorText v-else-if="stats.error" :text="stats.error.message" />
          <Loading v-else />
        </section>
      </div>

      <aside class="card col gap-8 work-side">
        <div class="row top">
          <span class="work-ic"><Icon name="storefront" /></span>
          <div class="col gap-4 grow">
            <h3 class="t-title-m">{{ ph.name }}</h3>
            <span v-if="ph.status !== 'approved'" class="t-label-l c-primary">{{ statusText(ph.status) }}</span>
          </div>
          <StatusTag :status="ph.status" :closed="ph.closedUntil > 0" />
        </div>
        <StatusReason :status="ph.status" :reason="ph.statusReason" />
        <p v-if="ph.status === 'pending'" class="t-body-s muted">{{ tr('You\'ll appear in the list after approval.', 'অনুমোদনের পর তালিকায় দেখাবে।') }}</p>
        <span v-if="hours" class="icon-line"><Icon name="schedule" /><span>{{ hours }}</span></span>
        <span class="icon-line"><Icon name="call" /><span class="num">{{ num(ph.phone) }}</span></span>
        <span v-if="place" class="icon-line"><Icon name="location_on" /><span>{{ place }}</span></span>
        <span class="icon-line"><Icon name="badge" /><span>{{ tr('Drug licence number', 'ড্রাগ লাইসেন্স নম্বর') }}: {{ ph.licenseNo }}</span></span>
        <span v-if="ph.discount > 0" class="icon-line"><Icon name="payments" /><span>{{ tr(`${ph.discount}% off MRP`, `MRP থেকে ${num(ph.discount)}% ছাড়`) }}</span></span>
        <span v-if="(ph.discountMin ?? 0) > 0 || ph.discountNote || (ph.freeDeliveryOver ?? 0) > 0" class="icon-line"><Icon name="info" /><span>{{ terms }}</span></span>
        <span class="icon-line"><Icon name="local_shipping" /><span>{{ ph.delivery ? tr('Home delivery', 'বাসায় ডেলিভারি') + (ph.deliveryNote ? ' · ' + ph.deliveryNote : '') : tr('No home delivery', 'বাসায় ডেলিভারি নেই') }}</span></span>
        <hr class="divider" />
        <template v-if="ph.status !== 'suspended'">
          <div v-if="ph.closedUntil > 0" class="col gap-8">
            <span class="icon-line"><Icon name="info" /><span>{{ tr('Closed until ', 'বন্ধ থাকবে ') + formatDate(ph.closedUntil) + tr('', ' পর্যন্ত') }}</span></span>
            <Button variant="tonal" icon="storefront" :busy="busy" @click="act(tr('Opened again', 'আবার খোলা হয়েছে'), () => call('pharmacy_pause', { until: null }))">{{ tr('Open again', 'আবার খুলুন') }}</Button>
          </div>
          <Button v-else variant="tonal" icon="schedule" :disabled="busy" @click="pausing = true">{{ tr('Closed for some days', 'কয়েক দিন বন্ধ থাকবে') }}</Button>
        </template>
        <AppealButton v-if="ph.status === 'suspended' || ph.status === 'rejected'" category="pharmacy" />
      </aside>
    </div>
    <DangerZone v-if="ph.status !== 'suspended'">
      <Button variant="danger-text" icon="delete" :disabled="busy" @click="askDel">{{ tr('Remove my pharmacy', 'ফার্মেসি মুছে ফেলুন') }}</Button>
    </DangerZone>

    <Dialog v-if="pausing" :title="tr('Closed for how long?', 'কত দিন বন্ধ থাকবে?')" size="small" @close="pausing = false">
      <div class="work-days">
        <Button v-for="d in [1, 3, 7, 15, 30]" :key="d" variant="outline" @click="pause(d)">{{ tr(`${d} day(s)`, `${num(d)} দিন`) }}</Button>
      </div>
      <template #actions><Button variant="text" @click="pausing = false">{{ tr('Cancel', 'বাতিল') }}</Button></template>
    </Dialog>
    <ConfirmDialog
      v-if="del"
      :title="tr('Remove your pharmacy?', 'ফার্মেসি মুছে ফেলবেন?')"
      :text="tr('It will disappear from the list. You can add it again later (it will need approval again).', 'তালিকা থেকে সরে যাবে। পরে আবার যোগ করা যাবে (আবার অনুমোদন লাগবে)।')"
      :confirm="tr('Remove', 'মুছে ফেলুন')"
      danger
      :busy="busy"
      :error="delErr"
      @confirm="remove"
      @close="del = false"
    />
  </BizPage>
</template>
