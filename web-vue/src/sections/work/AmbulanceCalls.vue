<script setup lang="ts">
// "ডাক" (২.২৮.০) — অ্যাম্বুলেন্সের মালিক/চালকের পাতার উপরে (অ্যাপের AmbulanceCallsSection):
// খোলা অনুরোধ (দূরত্ব, ঠিকানা, নোট, কতক্ষণ আগে) — "আসছি" / "পারব না"; নিজের নেওয়াটা — রোগীর নাম, ফোন,
// "ম্যাপে খুলুন" (ফোনের/ব্রাউজারের নিজের ম্যাপ, আলাদা খরচ নেই), "পৌঁছেছি" / "শেষ" / "বাতিল"।
// রাজির আগে রোগীর ফোন, নাম, ঠিক জায়গা আসেই না (সার্ভার দেয় না)।
// ভাড়া (২.৩০.০): গন্তব্য, ধরন, আনুমানিক ভাড়া — "আসছি — ৳X" (X বদলানো যায়, রোগীর সর্বোচ্চের বেশি নয়) = প্রস্তাব;
// তারপর "রোগীর উত্তরের অপেক্ষা…"; রোগী "রাজি" চাপলে তবেই "আপনি যাচ্ছেন" (ঠিক হওয়া ভাড়াসহ)।
import { computed, ref } from 'vue';
import { Button, ErrorText, Icon, IconLine, InfoText, Tag } from '@/ui/kit';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { timeAgo } from '@/lib/format';
import { DRIVER_REASONS, reqTypeLabel } from '@/sections/services/ambreq';
import ReasonDialog from '@/sections/services/ReasonDialog.vue';
import { parseTaka, taka } from '@/sections/services/fare';
import { mapLink, type DriverReq } from './ambcalls';

const props = defineProps<{ names: Record<string, string>; onChanged: () => void }>();
// পাতা খোলা থাকলে ৫ সেকেন্ড পরপর (পেছনের ট্যাবে ব্রাউজার নিজেই থামায়)
const q = useCall<{ items: DriverReq[] }>('ambreq_driver', {}, { refetchInterval: 5000, staleTime: 0 });
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const info = ref<string | null>(null);
const cancel = ref<DriverReq | null>(null);
// প্রতিটা ডাকের ভাড়ার ঘর (না লিখলে আনুমানিক ভাড়া)
const fares = ref<Record<string, string>>({});
const items = computed(() => q.data?.items ?? []);

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
    <p v-if="q.data && !items.length" class="t-body-s muted">{{ tr('When a patient nearby calls an ambulance, it shows here. Keep "Available now" on.', 'কাছের কোনো রোগী অ্যাম্বুলেন্স ডাকলে এখানে আসবে। "এখন খালি আছি" চালু রাখুন।') }}</p>
    <article v-for="r in items" :key="r.id" :class="r.status !== 'open' ? 'amb-call mine' : 'amb-call'" data-testid="amb-call">
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
      <template v-if="r.status !== 'open'">
        <IconLine v-if="r.patientName" icon="person_outline">{{ r.patientName }}</IconLine>
        <div class="row wrap gap-8">
          <a v-if="r.phone" class="btn square has-icon" :href="`tel:${r.phone}`"><Icon name="call" />{{ num(r.phone) }}</a>
          <!-- নিজের ম্যাপ (খরচ নেই): ঠিক জায়গা থাকলে সেটা, নইলে ঠিকানা আর এলাকার লেখা দিয়ে খোঁজা -->
          <a class="btn outline square has-icon" :href="mapLink(r)" target="_blank" rel="noopener noreferrer" data-testid="amb-open-map"><Icon name="map" />{{ tr('Open in map', 'ম্যাপে খুলুন') }}</a>
        </div>
        <div class="row wrap gap-8">
          <Button v-if="r.status === 'accepted'" :busy="busy === r.id" :disabled="!!busy" @click="act(r, () => call('ambreq_step', { id: r.id, step: 'arrived' }))">{{ tr('Arrived', 'পৌঁছেছি') }}</Button>
          <Button :busy="busy === r.id" :disabled="!!busy" @click="act(r, () => call('ambreq_step', { id: r.id, step: 'done' }), true, tr('Trip finished. You\'re available again.', 'ট্রিপ শেষ। আপনি আবার খালি।'))">{{ tr('Finish', 'শেষ') }}</Button>
          <span class="grow" />
          <Button variant="danger-text" :disabled="!!busy" @click="cancel = r">{{ tr('Cancel', 'বাতিল') }}</Button>
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
        <div class="row gap-8">
          <Button icon="check_circle" class="grow" :busy="busy === r.id" :disabled="!!busy || !quoteOf(r).okFare" data-testid="amb-accept" @click="act(r, () => call('ambreq_accept', { id: r.id, fare: quoteOf(r).fare }), true)">
            {{ tr('Coming', 'আসছি') + (quoteOf(r).okFare ? ` — ${taka(quoteOf(r).fare)}` : '') }}
          </Button>
          <Button variant="outline" :disabled="!!busy" @click="act(r, () => call('ambreq_decline', { id: r.id }))">{{ tr('Can\'t', 'পারব না') }}</Button>
        </div>
      </div>
      <div v-else class="row gap-8">
        <Button icon="check_circle" class="grow" :busy="busy === r.id" :disabled="!!busy" data-testid="amb-accept" @click="act(r, () => call('ambreq_accept', { id: r.id }), true)">{{ tr('Coming', 'আসছি') }}</Button>
        <Button variant="outline" :disabled="!!busy" @click="act(r, () => call('ambreq_decline', { id: r.id }))">{{ tr('Can\'t', 'পারব না') }}</Button>
      </div>
    </article>
    <ReasonDialog v-if="cancel" :title="tr('Why cancel?', 'কেন বাতিল করছেন?')" :reasons="DRIVER_REASONS()" :on-pick="pickReason" @close="cancel = null" />
  </section>
</template>
