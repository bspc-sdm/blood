<script setup lang="ts">
// নিশ্চিত করার ঘর: উপরে গাড়ির ধরন (বাধ্যতামূলক, "যেকোনো" আগে থেকে; তালিকায় ছাঁকা ধরন থাকলে সেটা), তারপর "কোথা থেকে নেবে"
// (LocationInput), "কোথায় যাবেন" (DestField), অক্সিজেন, ভাড়া সর্বোচ্চ, নোট, মোবাইল।
// gps: আগে থেকে পাওয়া ব্রাউজারের লোকেশন (বেছে নেওয়া জেলার ভেতরে); target: বিস্তারিত থেকে একটা গাড়ি — সেটা আগে
import { computed, ref, watch } from 'vue';
import { Button, Check, ChoiceChips, Dialog, ErrorText, TextArea, TextInput } from '@/ui/kit';
import { call, errorText, ServerError } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { queryClient } from '@/lib/query';
import { FARE_LEVELS, parseTaka, taka, type Dest } from './fare';
import { locationError, type LocationValue } from './location';
import { LAST_AREA, LAST_BUDGET, LAST_DEST, LAST_PICKUP, REQ_TYPES, type AmbReq, type FareCount } from './ambreq';
import LocationInput from './LocationInput.vue';
import DestField from './DestField.vue';
import FareCountLine from './FareCountLine.vue';

const props = defineProps<{ district: string; upazilaId: number | null; type: string | null; gps: { lat: number; lng: number } | null; target: { id: string; name: string } | null }>();
const emit = defineEmits<{ close: [] }>();
const s = useSession();
const nav = useNav();
const kind = ref<string>(props.type && REQ_TYPES().some((t) => t.value === props.type) ? props.type : 'any');
const loc = ref<LocationValue>((() => {
  const last = store.getJson<{ district: string; upazilaId: number | null; unionId: number | null }>(LAST_AREA);
  const same = last && last.district === props.district && (props.upazilaId == null || last.upazilaId === props.upazilaId);
  return {
    district: props.district,
    upazilaId: props.upazilaId ?? (same ? last!.upazilaId : null),
    unionId: same ? last!.unionId ?? null : null,
    landmark: store.get(LAST_PICKUP) ?? '',
    point: props.gps,
    pointFrom: props.gps ? 'gps' : null,
  };
})());
const note = ref('');
const phone = ref(s.profile?.phone ?? '');
const busy = ref(false);
const tried = ref(false);
const err = ref<string | null>(null);
// ভাড়া: গন্তব্য (শেষবারেরটা), অক্সিজেন, বাজেট (চিপ: ১০০০/২০০০/৫০০০/১০০০০/যেকোনো/নিজে লেখা), ভাড়া না জানা গাড়িও
const dest = ref<Dest | null>(store.getJson<Dest>(LAST_DEST));
const oxygen = ref(false);
const budget = ref<string>((() => {
  const b = store.get(LAST_BUDGET);
  return b && (b === 'any' || FARE_LEVELS.includes(Number(b))) ? b : b ? 'custom' : 'any';
})());
const custom = ref((() => {
  const b = store.get(LAST_BUDGET);
  return b && b !== 'any' && !FARE_LEVELS.includes(Number(b)) ? b : '';
})());
const unknown = ref(false);
const maxFare = computed<number | null>(() => {
  const customFare = parseTaka(custom.value);
  return budget.value === 'any' ? null : budget.value === 'custom' ? (customFare != null && !Number.isNaN(customFare) ? customFare : NaN) : Number(budget.value);
});
const budgetBad = computed(() => Number.isNaN(maxFare.value as number) || (maxFare.value != null && maxFare.value < 100));
const carType = computed(() => (kind.value === 'any' ? null : kind.value));
const where = computed(() => ({ district: loc.value.district, upazilaId: loc.value.upazilaId, ...(loc.value.point ? { lat: loc.value.point.lat, lng: loc.value.point.lng, gps: true } : {}) }));
// সাথে সাথে গোনা (৩৫০ মি.সে. থেমে) — শুধু গন্তব্য আর ঠিক বাজেট থাকলে
const count = ref<FareCount | null>(null);
const counting = ref(false);
const countKey = computed(() => JSON.stringify([where.value, carType.value, dest.value, maxFare.value, unknown.value, oxygen.value]));
watch(
  () => [countKey.value, budgetBad.value] as const,
  (_n, _o, onCleanup) => {
    if (!dest.value || budgetBad.value) {
      count.value = null;
      return;
    }
    let live = true;
    counting.value = true;
    const body = { ...where.value, type: carType.value, dest: dest.value, maxFare: maxFare.value, includeUnknown: unknown.value, oxygen: oxygen.value };
    const t = setTimeout(() => {
      call<FareCount>('ambreq_count', body)
        .then((c) => live && (count.value = c), () => live && (count.value = null))
        .finally(() => live && (counting.value = false));
    }, 350);
    onCleanup(() => {
      live = false;
      clearTimeout(t);
    });
  },
  { immediate: true },
);
const raise = (to: number | null) => {
  if (to == null) budget.value = 'any';
  else if (FARE_LEVELS.includes(to)) budget.value = String(to);
  else {
    budget.value = 'custom';
    custom.value = String(to);
  }
};
const budgetOpts = computed(() => [...FARE_LEVELS.map((l) => ({ value: String(l), label: taka(l) })), { value: 'any', label: tr('Any', 'যেকোনো') }, { value: 'custom', label: tr('Other…', 'অন্য…') }]);

async function send() {
  tried.value = true;
  const e = locationError(loc.value)
    ?? (!dest.value ? tr('Choose where to go', '"কোথায় যাবেন" বেছে নিন') : null)
    ?? (budgetBad.value ? tr('Write the fare limit in whole taka (৳100 or more)', 'সর্বোচ্চ ভাড়া পুরো টাকায় লিখুন (৳১০০ বা বেশি)') : null)
    ?? (count.value && count.value.count === 0 ? tr('No ambulance within this fare — raise the fare', 'এই ভাড়ায় কোনো গাড়ি নেই — ভাড়া বাড়ান') : null)
    ?? (!/^(\+?880|0)?1[3-9]\d{8}$/.test(phone.value.replace(/[\s-]/g, '')) ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন') : null);
  err.value = e;
  if (e) return;
  busy.value = true;
  const l = loc.value;
  try {
    const r = await call<{ id: string }>('ambreq_create', {
      district: l.district, upazilaId: l.upazilaId, unionId: l.unionId, type: carType.value,
      dest: dest.value, maxFare: maxFare.value, includeUnknown: unknown.value, oxygen: oxygen.value,
      pickup: l.landmark.trim(), note: note.value.trim(), phone: phone.value.replace(/[\s-]/g, ''), ambulanceId: l.district === props.district ? props.target?.id ?? null : null,
      ...(l.point ? { lat: l.point.lat, lng: l.point.lng, gps: true } : {}),
    });
    store.set(LAST_PICKUP, l.landmark.trim());
    store.setJson(LAST_DEST, dest.value);
    store.set(LAST_BUDGET, maxFare.value == null ? 'any' : String(maxFare.value));
    if (!l.upazilaAuto) store.setJson(LAST_AREA, { district: l.district, upazilaId: l.upazilaId, unionId: l.unionId });
    queryClient.invalidateQueries({ queryKey: ['ambreq_status'] });
    nav(`/services/ambulance/call/${r.id}`);
  } catch (x) {
    // আগেই একটা চালু: সেটার পাতা
    if (x instanceof ServerError && x.code === 'amb_req_active') {
      const a = await call<{ request: AmbReq | null }>('ambreq_status', {}).catch(() => null);
      if (a?.request) return nav(`/services/ambulance/call/${a.request.id}`);
    }
    err.value = errorText(x);
    busy.value = false;
  }
}
function onLoc(v: LocationValue) {
  loc.value = v;
  err.value = null;
}
function onDest(d: Dest | null) {
  dest.value = d;
  err.value = null;
}
</script>

<template>
  <Dialog :title="tr('Call an ambulance', 'অ্যাম্বুলেন্স ডাকুন')" @close="!busy && emit('close')">
    <div class="col gap-6" data-testid="amb-type">
      <span class="t-title-s">{{ tr('Ambulance type', 'গাড়ির ধরন') }}</span>
      <ChoiceChips v-model="kind" :options="REQ_TYPES()" />
      <span v-if="kind === 'freezer'" class="t-body-s muted">{{ tr('Only freezer vans (for the deceased) get this request.', 'শুধু ফ্রিজার ভ্যান (মরদেহ নেওয়ার গাড়ি) এই অনুরোধ পাবে।') }}</span>
    </div>
    <p class="t-body-m">
      {{ target
        ? tr(`Goes to ${target.name} first, and to the nearest free ambulances within your fare. Drivers offer a fare — tap "Agree" on one.`, `আগে ${target.name}-কে, সাথে আপনার ভাড়ার মধ্যে কাছের খালি গাড়িগুলোকে যাবে। চালকরা ভাড়া বলবেন — একটায় "রাজি" চাপুন।`)
        : tr('Goes to the nearest free ambulances within your fare. Drivers offer a fare — tap "Agree" on one.', 'আপনার ভাড়ার মধ্যে কাছের খালি গাড়িগুলোতে একসাথে যাবে। চালকরা ভাড়া বলবেন — একটায় "রাজি" চাপুন।') }}
    </p>
    <LocationInput :value="loc" :show-errors="tried" @change="onLoc" />
    <div class="col gap-6" data-testid="amb-dest">
      <DestField
        :label="tr('Where to go', 'কোথায় যাবেন')"
        :district="loc.district"
        :near="loc.point"
        :value="dest"
        :error="tried && !dest ? tr('Choose where to go', '&quot;কোথায় যাবেন&quot; বেছে নিন') : null"
        @change="onDest"
      />
      <Check v-model="oxygen">{{ tr('Oxygen needed', 'অক্সিজেন লাগবে') }}</Check>
    </div>
    <div class="col gap-6 amb-budget" data-testid="amb-budget">
      <span class="t-title-s">{{ tr('Fare up to', 'ভাড়া সর্বোচ্চ') }}</span>
      <ChoiceChips v-model="budget" :options="budgetOpts" />
      <TextInput
        v-if="budget === 'custom'"
        v-model="custom"
        :label="tr('Fare up to (৳)', 'সর্বোচ্চ ভাড়া (৳)')"
        inputmode="numeric"
        maxlength="7"
        autofocus
        :error="custom && budgetBad ? tr('Whole taka, ৳100 or more', 'পুরো টাকায়, ৳১০০ বা বেশি') : undefined"
      />
      <Check v-model="unknown">{{ tr('Also ambulances with unknown fare', 'ভাড়া জানা নেই এমন গাড়িও') }}</Check>
      <FareCountLine :count="count" :counting="counting" :dest="!!dest" :include-unknown="unknown" @raise="raise" @unknown="unknown = true" />
    </div>
    <TextArea v-model="note" :label="tr('Note (optional) — e.g. the patient can\'t walk', 'নোট (ঐচ্ছিক) — যেমন রোগী হাঁটতে পারেন না')" rows="2" maxlength="200" />
    <TextInput
      :label="tr('Your mobile (for the driver)', 'আপনার মোবাইল (চালকের জন্য)')"
      inputmode="tel"
      :model-value="phone"
      maxlength="15"
      @update:model-value="(v: string) => (phone = v.replace(/[^\d+\s-]/g, ''))"
    />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button icon="send" :busy="busy" :disabled="!!count && count.count === 0" data-testid="amb-send" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
