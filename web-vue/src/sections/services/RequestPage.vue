<script setup lang="ts">
// অপেক্ষার পাতা (/services/ambulance/call/:id): প্রস্তাব, অবস্থা, ফোন করা গাড়ি, বাতিল
import { computed, ref, watch } from 'vue';
import { Button, EmptyState, ErrorText, Icon, IconLine, Loading, useNow } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { call, errorText } from '@/lib/api';
import { useCall, queryClient } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { store } from '@/lib/store';
import { useNav } from '@/lib/nav';
import { taka } from './fare';
import { ASK_KEY, PATIENT_REASONS, isActive, kmText, markCalled, reasonText, reqTypeLabel, useResumeTick, type AmbCar, type AmbQuote, type AmbReq } from './ambreq';
import ReasonDialog from './ReasonDialog.vue';
import PickCarDialog from './PickCarDialog.vue';
import RatingDialog from './RatingDialog.vue';
import AmbHead from './AmbHead.vue';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';

const props = defineProps<{ id: string }>();
const nav = useNav();
const now = useNow(1000);
const tick = useResumeTick();
const cancel = ref(false);
const asking = ref(false);
const replacing = ref(false);
const okayed = ref<string | null>(null);
const rating = ref(false);
const agreeing = ref<string | null>(null);
const raising = ref(false);
const fareErr = ref<string | null>(null);
const fareInfo = ref<string | null>(null);
const q = useCall<{ request: AmbReq | null }>('ambreq_status', () => ({ id: props.id }), {
  staleTime: 0,
  // অপেক্ষায় ৪ সেকেন্ড পরপর, রাজির পরে ১০ সেকেন্ড, বন্ধ হলে থামে (পেছনের ট্যাবে ব্রাউজার নিজেই থামায়)
  refetchInterval: (query: any) => {
    const r = (query.state.data as { request: AmbReq | null } | undefined)?.request;
    return !r ? 4000 : r.status === 'open' ? 4000 : isActive(r) ? 10000 : false;
  },
});
const r = computed(() => q.data?.request ?? null);
const set = (x: AmbReq) => {
  queryClient.setQueriesData({ queryKey: ['ambreq_status', { id: props.id }] }, { request: x });
  queryClient.invalidateQueries({ queryKey: ['ambreq_status'] });
};
// ফোন করে ফিরে এলে একবার: "ফোনে কোনো গাড়ি পেয়েছেন?"
let asked = 0;
watch(
  () => [tick.value, r.value] as const,
  ([t, x]) => {
    if (x && x.status === 'open' && x.called.length && store.get(ASK_KEY) === props.id && asked !== t + 1) {
      asked = t + 1;
      store.set(ASK_KEY, null);
      asking.value = true;
    }
  },
  { immediate: true },
);

const title = computed(() => tr('Calling an ambulance', 'অ্যাম্বুলেন্স ডাকা'));
const left = computed(() => (r.value ? Math.max(0, Math.round((r.value.expiresAt - now.value) / 1000)) : 0));
const quotes = computed(() => (r.value?.status === 'open' ? (r.value.quotes ?? []).filter((x) => x.expiresAt > now.value) : []));
const secsOf = (x: AmbQuote) => Math.max(0, Math.round((x.expiresAt - now.value) / 1000));
const mmss = (secs: number) => num(`${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`);
async function agree(x: AmbQuote) {
  agreeing.value = x.id;
  fareErr.value = null;
  try {
    set((await call<{ request: AmbReq }>('ambreq_agree', { id: props.id, ambulanceId: x.id })).request);
  } catch (e) {
    fareErr.value = errorText(e);
    q.refetch();
  } finally {
    agreeing.value = null;
  }
}
async function raiseBudget(maxFare: number | null, includeUnknown = false) {
  raising.value = true;
  fareErr.value = null;
  fareInfo.value = null;
  try {
    const out = await call<{ added: number; request: AmbReq }>('ambreq_budget', { id: props.id, maxFare, includeUnknown: includeUnknown || !!r.value?.includeUnknown });
    set(out.request);
    fareInfo.value = out.added
      ? tr(`Sent to ${out.added} more ambulance${out.added > 1 ? 's' : ''}`, `আরও ${num(out.added)}টা গাড়িকে জানানো হয়েছে`)
      : tr('No new ambulance within this fare — drivers already notified can now offer up to it', 'এই ভাড়ায় নতুন কোনো গাড়ি নেই — আগে জানানো চালকরা এখন এই পর্যন্ত বলতে পারবেন');
  } catch (e) {
    fareErr.value = errorText(e);
  } finally {
    raising.value = false;
  }
}
const others = computed(() => (r.value ? r.value.called.filter((c) => c.id !== r.value!.ambulance?.id) : []));
const callCar = (c: AmbCar) => {
  if (isActive(r.value)) markCalled(props.id);
  call('ambulance_called', { id: c.id, ...(isActive(r.value) ? { reqId: props.id } : {}) }).catch(() => {});
};
const quoteMeta = (x: AmbQuote) =>
  [x.types.map((t) => reqTypeLabel(t)).join(', '), kmText(x.km, x.kmApprox), x.ratingCount ? `★ ${num(x.rating)} (${num(x.ratingCount)})` : null].filter(Boolean).join(' · ');
async function onCancel(reason: string) {
  await call('ambreq_cancel', { id: props.id, reason });
  cancel.value = false;
  await q.refetch();
  queryClient.invalidateQueries({ queryKey: ['ambreq_status'] });
}
async function onByPhone(c: AmbCar | null) {
  if (c) set((await call<{ request: AmbReq }>('ambreq_byphone', { id: props.id, ambulanceId: c.id })).request);
  asking.value = false;
}
async function onReplace(c: AmbCar | null) {
  set((await call<{ request: AmbReq }>('ambreq_replace', { id: props.id, ...(c ? { ambulanceId: c.id } : {}) })).request);
  replacing.value = false;
}
</script>

<template>
  <FormPage v-if="q.isLoading" :title="title" back-to="/services/ambulance"><Loading /></FormPage>
  <FormPage v-else-if="!r" :title="title" back-to="/services/ambulance">
    <EmptyState icon="ambulance" :title="q.isError ? q.error!.message : tr('Not found', 'পাওয়া যায়নি')" />
  </FormPage>
  <FormPage v-else :title="title" back-to="/services/ambulance">
    <div class="col gap-16 amb-wait" :data-status="r.status">
      <div v-if="r.status === 'open' && quotes.length" class="col gap-8" data-testid="amb-quotes">
        <h2 class="t-title-l">{{ tr('Fare offers — pick one', 'ভাড়ার প্রস্তাব — একটা বেছে নিন') }}</h2>
        <span class="t-body-s muted">{{ tr('Confirmed only when you tap "Agree". Each offer stays 2 minutes.', '"রাজি" চাপলে তবেই নিশ্চিত। প্রতিটা প্রস্তাব ২ মিনিট থাকে।') }}</span>
        <div v-for="x in quotes" :key="x.id" class="amb-quote" data-testid="amb-quote">
          <div class="col gap-2 grow">
            <span class="t-title-s">{{ x.name }}</span>
            <span class="t-body-s muted">{{ quoteMeta(x) }}</span>
            <span class="t-body-s muted num">{{ tr('Expires in ', 'থাকবে আর ') + mmss(secsOf(x)) }}</span>
          </div>
          <div class="col gap-6 amb-quote-act">
            <span class="amb-quote-fare">{{ taka(x.fare) }}</span>
            <Button small icon="check_circle" :busy="agreeing === x.id" :disabled="!!agreeing" data-testid="amb-agree" @click="agree(x)">{{ tr('Agree', 'রাজি') }}</Button>
          </div>
        </div>
      </div>
      <div v-if="r.status === 'open'" class="row gap-12">
        <span class="spinner" />
        <div class="col gap-2 grow">
          <h2 :class="quotes.length ? 't-title-s' : 't-title-l'">{{ quotes.length ? tr('Waiting for more offers…', 'আরও প্রস্তাবের অপেক্ষা…') : tr('Looking for an ambulance…', 'অ্যাম্বুলেন্স খোঁজা হচ্ছে…') }}</h2>
          <span class="t-body-m muted">
            {{ r.wave > 1
              ? tr(`Sent to ${r.notified} ambulances — more were added`, `${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে — আরও গাড়ি যোগ হয়েছে`)
              : r.quote
                ? tr(`Sent to ${r.notified} nearby ambulances within your fare. Offers will show here.`, `আপনার ভাড়ার মধ্যে কাছের ${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে। প্রস্তাব এলে এখানে দেখাবে।`)
                : tr(`Sent to ${r.notified} nearby ambulances. We'll tell you as soon as one accepts.`, `কাছের ${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে। কেউ রাজি হলেই জানাবো।`) }}
          </span>
          <span class="t-body-s muted num">{{ tr('Waiting: ', 'অপেক্ষা: ') + mmss(left) }}</span>
        </div>
      </div>
      <div v-if="(r.status === 'accepted' || r.status === 'arrived') && r.ambulance" class="amb-accepted">
        <div class="row gap-12">
          <span class="amb-accepted-ic"><Icon name="ambulance" /></span>
          <div class="col gap-2 grow">
            <span class="t-label-l">{{ r.status === 'arrived' ? tr('Arrived', 'পৌঁছেছে') : tr('Coming', 'আসছে') }}</span>
            <h2 class="t-title-l">{{ r.ambulance.name }}</h2>
            <span v-if="kmText(r.ambulance.km, r.ambulance.kmApprox)" class="t-body-m">{{ kmText(r.ambulance.km, r.ambulance.kmApprox) + tr(' away', ' দূরে') }}</span>
          </div>
        </div>
        <div v-if="r.agreedFare != null" class="amb-agreed" data-testid="amb-agreed-fare">
          <span class="t-label-l">{{ tr('Agreed fare', 'ঠিক হওয়া ভাড়া') }}</span>
          <span class="amb-agreed-fare">{{ taka(r.agreedFare) }}</span>
          <span v-if="r.destLabel" class="t-body-s">{{ tr('To ', 'গন্তব্য: ') + r.destLabel }}</span>
        </div>
        <a v-if="r.ambulance.phone" class="btn square has-icon block" :href="`tel:${r.ambulance.phone}`" @click="callCar(r.ambulance!)">
          <Icon name="call" />{{ tr('Call driver ', 'চালককে ফোন ') + num(r.ambulance.phone) }}
        </a>
        <ContactActions :message="chatOn()" @message="openChatAt" ctx="ambulance" :ref-id="id" :peer-name="r.ambulance.name" :phone="r.ambulance.phone || null" />
      </div>
      <template v-if="r.status === 'done'">
        <AmbHead icon="check_circle" :t="tr('Trip finished', 'ট্রিপ শেষ')" :sub="r.ambulance?.name" />
        <Button v-if="r.canRate && r.ambulance" icon="star" @click="rating = true">{{ tr('Rate the ambulance', 'অ্যাম্বুলেন্সকে রেটিং দিন') }}</Button>
      </template>
      <template v-if="r.status === 'byPhone'">
        <AmbHead icon="check_circle" :t="tr('You got an ambulance by phone', 'ফোনে গাড়ি পেয়েছেন')" :sub="r.ambulance?.name" />
        <a v-if="r.ambulance?.phone" class="btn outline square has-icon" :href="`tel:${r.ambulance.phone}`"><Icon name="call" />{{ tr('Call ', 'ফোন: ') + num(r.ambulance.phone) }}</a>
      </template>
      <AmbHead v-if="r.status === 'expired'" icon="warning" :t="tr('No ambulance accepted', 'কোনো অ্যাম্বুলেন্স রাজি হয়নি')" :sub="tr('Please call one directly below.', 'নিচে থেকে সরাসরি ফোন করুন।')" />
      <AmbHead
        v-if="r.status === 'cancelled'"
        icon="info"
        :t="r.cancelledBy === 'driver' ? tr(`${r.ambulance?.name ?? 'The driver'} cancelled`, `${r.ambulance?.name ?? 'চালক'} বাতিল করেছেন`) : tr('You cancelled the request', 'আপনি অনুরোধ বাতিল করেছেন')"
        :sub="reasonText(r.cancelReason)"
      />

      <div v-if="r.status === 'open' && r.quote && r.wave > 1 && !quotes.length" class="amb-raise col gap-8" data-testid="amb-raise-box">
        <span class="t-body-m">{{ tr('Nobody has offered yet. Raise the fare to reach more ambulances:', 'এখনো কেউ ভাড়া বলেননি। আরও গাড়ি পেতে ভাড়া বাড়ান:') }}</span>
        <div class="row wrap gap-8">
          <Button v-if="r.raiseTo != null" small icon="add" :busy="raising" data-testid="amb-raise" @click="raiseBudget(r.raiseTo!)">{{ tr(`Raise to ${taka(r.raiseTo)}`, `${taka(r.raiseTo)} পর্যন্ত বাড়ান`) }}</Button>
          <Button v-if="r.maxFare != null" small variant="outline" :busy="raising" @click="raiseBudget(null)">{{ tr('Any fare', 'যেকোনো ভাড়া') }}</Button>
          <Button v-if="!r.includeUnknown && (r.unknownFree ?? 0) > 0" small variant="outline" :busy="raising" @click="raiseBudget(r.maxFare ?? null, true)">{{ tr(`Include ${r.unknownFree} with unknown fare`, `ভাড়া জানা নেই এমন ${num(r.unknownFree!)}টাও`) }}</Button>
        </div>
      </div>
      <ErrorText :text="fareErr" />
      <span v-if="fareInfo" class="t-body-s muted" role="status">{{ fareInfo }}</span>

      <div v-if="r.fallback.length && ['open', 'expired', 'cancelled'].includes(r.status)" class="col gap-8">
        <h3 class="t-title-s">{{ r.status === 'open' ? tr('Or call one now:', 'অথবা এখনই ফোন করুন:') : tr('Nearest free ambulances:', 'কাছের খালি অ্যাম্বুলেন্স:') }}</h3>
        <div v-for="c in r.fallback" :key="c.id" class="amb-fallback">
          <div class="col gap-2 grow">
            <span class="t-title-s">{{ c.name }}</span>
            <span v-if="kmText(c.km, c.kmApprox)" class="t-body-s muted">{{ kmText(c.km, c.kmApprox) }}</span>
          </div>
          <a class="btn square has-icon small" :href="`tel:${c.phone}`" @click="callCar(c)"><Icon name="call" />{{ tr('Call', 'ফোন করুন') }}</a>
        </div>
      </div>

      <Button v-if="r.status === 'open' && r.called.length" variant="outline" icon="call" @click="asking = true">{{ tr('Got an ambulance by phone?', 'ফোনে কোনো গাড়ি পেয়েছেন?') }}</Button>

      <div v-if="(r.status === 'accepted' || r.status === 'arrived') && others.length && okayed !== r.ambulance?.id" class="amb-replace col gap-8">
        <span class="t-body-m">{{ tr(`${r.ambulance?.name} is coming. Did you also book another one by phone?`, `${r.ambulance?.name} আসছে। ফোনে অন্য গাড়িও ঠিক করেছেন?`) }}</span>
        <div class="row wrap gap-8">
          <Button @click="okayed = r.ambulance?.id ?? null">{{ tr('OK', 'ঠিক আছে') }}</Button>
          <Button variant="outline" @click="replacing = true">{{ tr('Got another by phone — cancel this one', 'ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল') }}</Button>
        </div>
      </div>

      <template v-if="isActive(r)">
        <div class="card col gap-6" data-testid="amb-req-info">
          <IconLine icon="ambulance">{{ tr('Type: ', 'ধরন: ') + reqTypeLabel(r.type) }}</IconLine>
          <IconLine icon="map">{{ areaLabel(r.district, r.upazilaId, r.unionId) + (r.gps ? tr(' · current location given', ' · এখনকার জায়গা দেওয়া') : '') }}</IconLine>
          <IconLine icon="location_on">{{ r.pickup }}</IconLine>
          <IconLine v-if="r.quote && r.destLabel" icon="local_hospital">{{ tr('Going to: ', 'যাবেন: ') + r.destLabel + (r.oxygen ? tr(' · oxygen', ' · অক্সিজেন') : '') }}</IconLine>
          <IconLine v-if="r.quote && r.status === 'open'" icon="tune">{{ tr('Fare up to: ', 'ভাড়া সর্বোচ্চ: ') + (r.maxFare != null ? taka(r.maxFare) : tr('any', 'যেকোনো')) }}</IconLine>
          <IconLine v-if="r.note" icon="info">{{ r.note }}</IconLine>
        </div>
        <Button variant="danger-text" @click="cancel = true">{{ tr('Cancel request', 'অনুরোধ বাতিল করুন') }}</Button>
      </template>
      <Button v-else variant="outline" icon="arrow_back" @click="nav('/services/ambulance')">{{ tr('Back to the list', 'তালিকায় ফিরুন') }}</Button>
      <a class="btn danger-text square has-icon small" href="tel:999"><Icon name="call" />{{ tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা') }}</a>
    </div>

    <ReasonDialog v-if="cancel" :title="tr('Why cancel?', 'কেন বাতিল করছেন?')" :reasons="PATIENT_REASONS()" :on-pick="onCancel" @close="cancel = false" />
    <PickCarDialog v-if="asking" :title="tr('Got an ambulance by phone?', 'ফোনে কোনো গাড়ি পেয়েছেন?')" :cars="r.called" :no-label="tr('No, keep looking', 'না, খুঁজতে থাকুন')" :on-pick="onByPhone" @close="asking = false" />
    <PickCarDialog v-if="replacing" :title="tr('Which one did you get by phone?', 'ফোনে কোন গাড়ি পেয়েছেন?')" :cars="others" :no-label="tr('None — keep looking', 'কোনোটা না — আবার খুঁজুন')" :on-pick="onReplace" @close="replacing = false" />
    <RatingDialog v-if="rating && r.ambulance" kind="ambulance" :id="r.ambulance.id" :name="r.ambulance.name" :agreed-fare="r.agreedFare ?? null" @close="rating = false" />
  </FormPage>
</template>
