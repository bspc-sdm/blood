<script setup lang="ts">
// "ডাক" (২.২৮.০) — অ্যাম্বুলেন্সের মালিক/চালকের পাতার উপরে (অ্যাপের AmbulanceCallsSection):
// খোলা অনুরোধ (দূরত্ব, ঠিকানা, নোট, কতক্ষণ আগে) — "আসছি" / "পারব না"; নিজের নেওয়াটা — রোগীর নাম, ফোন,
// "ম্যাপে খুলুন" (ফোনের/ব্রাউজারের নিজের ম্যাপ, আলাদা খরচ নেই), "পৌঁছেছি" / "শেষ" / "বাতিল"।
// রাজির আগে রোগীর ফোন, নাম, ঠিক জায়গা আসেই না (সার্ভার দেয় না)।
// ভাড়া (২.৩০.০): গন্তব্য, ধরন, আনুমানিক ভাড়া — "আসছি — ৳X" (X বদলানো যায়, রোগীর সর্বোচ্চের বেশি নয়) = প্রস্তাব;
// তারপর "রোগীর উত্তরের অপেক্ষা…"; রোগী "রাজি" চাপলে তবেই "আপনি যাচ্ছেন" (ঠিক হওয়া ভাড়াসহ)।
import { computed, ref } from 'vue';
import { Button, ErrorText, Icon, IconLine, InfoText, RowMenu, Tag } from '@/ui/kit';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { timeAgo } from '@/lib/format';
import { DRIVER_REASONS, reqTypeLabel } from '@/sections/services/ambreq';
import ReasonDialog from '@/sections/services/ReasonDialog.vue';
import { parseTaka, taka } from '@/sections/services/fare';
import { mapLink, type DriverReq } from './ambcalls';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';
import QueueSegs, { type QSeg } from '@/ui/listing/QueueSegs.vue';
import { useTwoPane } from '@/shell/chrome';

const props = defineProps<{ names: Record<string, string>; onChanged: () => void }>();
// পাতা খোলা থাকলে ৫ সেকেন্ড পরপর (পেছনের ট্যাবে ব্রাউজার নিজেই থামায়)
const q = useCall<{ items: DriverReq[]; counts?: { new?: number; active?: number } }>('ambreq_driver', {}, { refetchInterval: 5000, staleTime: 0 });
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const info = ref<string | null>(null);
const cancel = ref<DriverReq | null>(null);
// প্রতিটা ডাকের ভাড়ার ঘর (না লিখলে আনুমানিক ভাড়া)
const fares = ref<Record<string, string>>({});
const all = computed(() => q.data?.items ?? []);
// ভাগ (সেবার "আমার কাজ"-এর এক ছাঁচ): নতুন = খোলা ডাক, চলছে = নিজের নেওয়া (যাচ্ছি/পৌঁছেছি), শেষ = এই পাতায় শেষ করা ট্রিপ
// (সার্ভার শুধু খোলা আর চলমান ডাক দেয়)। না বাছলে: চলমান থাকলে "চলছে", নইলে "নতুন"।
const finished = ref<DriverReq[]>([]);
const picked = ref<QSeg | null>(null);
const segOf = (r: DriverReq): QSeg => (r.status === 'open' ? 'new' : 'active');
// সার্ভারের counts (ambreq_driver, ২০২৬-১০) আগে, না থাকলে তালিকা গুনে
const counts = computed(() => ({ new: q.data?.counts?.new ?? all.value.filter((r) => segOf(r) === 'new').length, active: q.data?.counts?.active ?? all.value.filter((r) => segOf(r) === 'active').length, done: finished.value.length }));
const seg = computed<QSeg>(() => picked.value ?? (counts.value.active ? 'active' : 'new'));
const items = computed(() => (seg.value === 'done' ? finished.value : all.value.filter((r) => segOf(r) === seg.value)));
// ডেস্কটপে (৯৬০px+) তালিকা-বিস্তারিত: বাঁয়ে ছোট সারি, ডানে বাছা ডাক (না বাছলে প্রথমটা)
const md = useTwoPane();
const pickedId = ref<string | null>(null);
const sel = computed(() => items.value.find((r) => r.id === pickedId.value) ?? items.value[0] ?? null);
const cards = computed(() => (md.value ? (sel.value ? [sel.value] : []) : items.value));
const rowTitle = (r: DriverReq) => (seg.value === 'done' ? tr('Finished', 'শেষ হয়েছে') : r.status === 'arrived' ? tr('You arrived', 'আপনি পৌঁছেছেন') : r.status !== 'open' ? tr('You\'re going', 'আপনি যাচ্ছেন') : tr('Ambulance needed', 'অ্যাম্বুলেন্স দরকার'));
const finish = (r: DriverReq) =>
  act(r, async () => {
    await call('ambreq_step', { id: r.id, step: 'done' });
    finished.value = [r, ...finished.value.filter((x) => x.id !== r.id)];
  }, true, tr('Trip finished. You\'re available again.', 'ট্রিপ শেষ। আপনি আবার খালি।'));

async function act(r: DriverReq, f: () => Promise<unknown>, changed = false, done?: string) {
  busy.value = r.id;
  err.value = null;
  info.value = null;
  try {
    await f();
    if (done) info.value = done;
    if (changed) props.onChanged();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = null;
    q.refetch();
  }
}
const kmText = (r: DriverReq) =>
  r.km == null ? null : r.kmApprox ? tr(`about ${Math.max(1, r.km)} km`, `প্রায় ${num(Math.max(1, r.km))} কিমি`) : r.km < 1 ? tr('< 1 km', '১ কিমির কম') : `${num(r.km)}${tr(' km', ' কিমি')}`;
function quoteOf(r: DriverReq) {
  const text = fares.value[r.id] ?? (r.suggestedFare != null ? String(r.suggestedFare) : '');
  const fare = parseTaka(text);
  const over = fare != null && !Number.isNaN(fare) && r.maxFare != null && fare > r.maxFare;
  const okFare = fare != null && !Number.isNaN(fare) && fare >= 100 && !over;
  return { text, fare, over, okFare };
}
async function pickReason(reason: string) {
  const c = cancel.value!;
  await call('ambreq_step', { id: c.id, step: 'cancel', reason });
  cancel.value = null;
  props.onChanged();
  q.refetch();
}
</script>

<template>
  <section class="col gap-8 amb-calls" :aria-label="tr('Calls', 'ডাক')">
    <h2 class="t-title-m row gap-8"><Icon name="notifications_active" />{{ tr('Calls', 'ডাক') }}</h2>
    <ErrorText :text="err" />
    <InfoText :text="info" />
    <!-- কোনো ডাক নেই (নতুন, চলমান বা এখানে শেষ করা): এক লাইন, ভাগের সারি নয় (আমার কাজ CONTRACT §২) -->
    <div v-if="!q.data && !q.error" class="q-empty-line" data-testid="amb-calls-loading"><span class="spinner small" /><span class="t-body-m">{{ tr('Loading…', 'লোড হচ্ছে…') }}</span></div>
    <div v-else-if="!all.length && !finished.length" class="q-empty-line" data-testid="amb-calls-empty">
      <Icon name="notifications_active" />
      <span class="t-body-m">{{ tr('No calls now — when a patient nearby calls, it shows here.', 'এখন কোনো ডাক নেই — কাছের রোগী ডাকলে এখানে আসবে।') }}</span><!-- bn-ok -->
    </div>
    <template v-else>
    <QueueSegs :label="tr('Calls', 'ডাক')" :value="seg" :counts="counts" @change="(k) => (picked = k as QSeg)" />
    <p v-if="seg === 'done' && !finished.length" class="t-body-s muted">{{ tr('Trips you finish here show up in this list.', 'এখানে শেষ করা ট্রিপ এই তালিকায় থাকবে।') }}</p><!-- bn-ok -->
    <p v-else-if="!items.length" class="t-body-s muted">{{ seg === 'new' ? tr('No new calls now.', 'এখন নতুন কোনো ডাক নেই।') : tr('No trip in progress.', 'এখন কোনো চলমান ট্রিপ নেই।') }}</p><!-- bn-ok -->
    <div :class="md && items.length ? 'q-md' : 'col gap-8'">
    <ul v-if="md && items.length" class="q-list" :aria-label="tr('Calls', 'ডাক')">
      <li v-for="r in items" :key="r.id">
        <button type="button" class="q-item" :aria-current="r.id === sel?.id" data-testid="q-item" @click="pickedId = r.id">
          <span class="col gap-2 grow" :style="{ minWidth: 0 }">
            <span class="t-title-s">{{ rowTitle(r) }}</span>
            <span class="t-body-s muted">{{ [r.pickup, areaLabel(r.district, r.upazilaId, r.unionId)].filter(Boolean).join(' · ') }}</span>
          </span>
          <span class="t-body-s muted">{{ timeAgo(r.createdAt) }}</span>
        </button>
      </li>
    </ul>
    <article v-for="r in cards" :key="r.id" :class="[r.status !== 'open' ? 'amb-call mine' : 'amb-call', md && 'q-detail']" :data-testid="seg === 'done' ? 'amb-call-done' : 'amb-call'">
      <div class="row">
        <span class="t-title-m grow">
          {{ r.status !== 'open' ? (r.status === 'arrived' ? tr('You arrived', 'আপনি পৌঁছেছেন') : tr('You\'re going', 'আপনি যাচ্ছেন')) : tr('Ambulance needed', 'অ্যাম্বুলেন্স দরকার') }}
        </span>
        <span class="t-body-s muted">{{ timeAgo(r.createdAt) }}</span>
      </div>
      <div v-if="r.called"><Tag tone="error">{{ tr('The patient called you', 'রোগী আপনাকে ফোন করেছেন') }}</Tag></div>
      <IconLine icon="map">{{ (kmText(r) ? kmText(r) + tr(' away', ' দূরে') + ' · ' : '') + areaLabel(r.district, r.upazilaId, r.unionId) }}</IconLine>
      <IconLine icon="location_on">{{ r.pickup }}</IconLine>
      <IconLine v-if="r.note" icon="info">{{ r.note }}</IconLine>
      <IconLine icon="ambulance">{{ tr('Type: ', 'ধরন: ') + reqTypeLabel(r.type) + (r.oxygen ? tr(' · oxygen needed', ' · অক্সিজেন লাগবে') : '') }}</IconLine>
      <IconLine v-if="r.quote && r.destLabel" icon="local_hospital">{{ tr('Going to: ', 'যাবেন: ') + r.destLabel }}</IconLine>
      <div v-if="r.quote && r.status !== 'open' && r.agreedFare != null" class="amb-fare-agreed" data-testid="amb-agreed-fare">{{ tr('Agreed fare ', 'ঠিক হওয়া ভাড়া ') + taka(r.agreedFare) }}</div>
      <IconLine v-if="r.ambulanceId && names[r.ambulanceId]" icon="ambulance">{{ tr('Your vehicle: ', 'আপনার গাড়ি: ') + names[r.ambulanceId] }}</IconLine>
      <div v-if="seg === 'done'"><Tag tone="good">{{ tr('Finished', 'শেষ হয়েছে') }}</Tag></div>
      <template v-else-if="r.status !== 'open'">
        <IconLine v-if="r.patientName" icon="person_outline">{{ r.patientName }}</IconLine>
        <div class="row wrap gap-8">
          <!-- নিজের ম্যাপ (খরচ নেই): ঠিক জায়গা থাকলে সেটা, নইলে ঠিকানা আর এলাকার লেখা দিয়ে খোঁজা -->
          <a class="btn outline square has-icon" :href="mapLink(r)" target="_blank" rel="noopener noreferrer" data-testid="amb-open-map"><Icon name="map" />{{ tr('Open in map', 'ম্যাপে খুলুন') }}</a>
        </div>
        <!-- রোগীর নম্বর, কল (ফোন কল · অ্যাপ কল · ভিডিও কল) · মেসেজ -->
        <ContactActions :message="chatOn()" @message="openChatAt" ctx="ambulance" :ref-id="r.id" :peer-name="r.patientName" :phone="r.phone || null" :show="r.status === 'accepted' || r.status === 'arrived'" show-number />
        <!-- একটাই মূল বোতাম: পৌঁছেছি → শেষ; বাকিটা "আরও"-তে -->
        <div class="q-acts">
          <Button v-if="r.status === 'accepted'" icon="location_on" :busy="busy === r.id" :disabled="!!busy" data-testid="amb-arrived" @click="act(r, () => call('ambreq_step', { id: r.id, step: 'arrived' }))">{{ tr('Arrived', 'পৌঁছেছি') }}</Button>
          <Button v-else icon="check_circle" :busy="busy === r.id" :disabled="!!busy" data-testid="amb-finish" @click="finish(r)">{{ tr('Finish', 'শেষ') }}</Button>
          <span class="grow" />
          <RowMenu
            :items="[
              r.status === 'accepted' && { label: tr('Finish', 'শেষ'), icon: 'check_circle', disabled: !!busy, testid: 'amb-finish', onClick: () => finish(r) },
              { label: tr('Cancel', 'বাতিল'), icon: 'close', danger: true, disabled: !!busy, testid: 'amb-cancel', onClick: () => (cancel = r) },
            ]"
          />
        </div>
      </template>
      <div v-else-if="r.quote && r.myQuote" class="amb-quote-wait" data-testid="amb-quote-wait">
        <span class="t-title-s">{{ tr('Waiting for the patient…', 'রোগীর উত্তরের অপেক্ষা…') }}</span>
        <span class="t-body-s muted">{{ tr('Your fare ', 'আপনার ভাড়া ') + taka(r.myQuote.fare) + tr(' — you are confirmed only when the patient agrees.', ' — রোগী "রাজি" চাপলে তবেই নিশ্চিত।') }}</span>
      </div>
      <div v-else-if="r.quote" class="col gap-8">
        <div class="t-body-s muted">{{ r.suggestedFare != null ? tr('From your fare list: ', 'আপনার ভাড়ার তালিকা থেকে: ') + taka(r.suggestedFare) : tr('Not in your fare list — write your fare.', 'আপনার ভাড়ার তালিকায় নেই — ভাড়া লিখুন।') }}{{ r.maxFare != null ? tr(' · patient\'s limit ', ' · রোগীর সর্বোচ্চ ') + taka(r.maxFare) : '' }}{{ r.night ? tr(' · night', ' · রাত') : '' }}</div>
        <label class="amb-fare-input">
          <span>{{ tr('Your fare (৳)', 'আপনার ভাড়া (৳)') }}</span>
          <input class="input" inputmode="numeric" :value="quoteOf(r).text" maxlength="7" data-testid="amb-fare-input" @input="fares = { ...fares, [r.id]: ($event.target as HTMLInputElement).value }" />
        </label>
        <ErrorText v-if="quoteOf(r).over" :text="tr(`The patient's limit is ${taka(r.maxFare)} — you can't offer more.`, `রোগীর সর্বোচ্চ ${taka(r.maxFare)} — এর বেশি দেওয়া যায় না।`)" />
        <div class="q-acts">
          <Button icon="check_circle" class="grow" :busy="busy === r.id" :disabled="!!busy || !quoteOf(r).okFare" data-testid="amb-accept" @click="act(r, () => call('ambreq_accept', { id: r.id, fare: quoteOf(r).fare }), true)">
            {{ tr('Coming', 'আসছি') + (quoteOf(r).okFare ? ` — ${taka(quoteOf(r).fare)}` : '') }}
          </Button>
          <RowMenu :items="[{ label: tr('Can\'t', 'পারব না'), icon: 'close', danger: true, disabled: !!busy, testid: 'amb-decline', onClick: () => act(r, () => call('ambreq_decline', { id: r.id })) }]" />
        </div>
      </div>
      <div v-else class="q-acts">
        <Button icon="check_circle" class="grow" :busy="busy === r.id" :disabled="!!busy" data-testid="amb-accept" @click="act(r, () => call('ambreq_accept', { id: r.id }), true)">{{ tr('Coming', 'আসছি') }}</Button>
        <RowMenu :items="[{ label: tr('Can\'t', 'পারব না'), icon: 'close', danger: true, disabled: !!busy, testid: 'amb-decline', onClick: () => act(r, () => call('ambreq_decline', { id: r.id })) }]" />
      </div>
    </article>
    </div>
    </template>
    <ReasonDialog v-if="cancel" :title="tr('Why cancel?', 'কেন বাতিল করছেন?')" :reasons="DRIVER_REASONS()" :on-pick="pickReason" @close="cancel = null" />
  </section>
</template>
