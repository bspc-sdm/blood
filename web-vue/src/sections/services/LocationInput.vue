<script setup lang="ts">
// অ্যাম্বুলেন্স ডাকার "কোথা থেকে নেবে" (২.৩১.০) — অ্যাপের LocationInput.kt-এর ওয়েব রূপ।
// তিনভাবে, সবই বিনামূল্যে (অ্যাপ/সাইটের ভেতরে ম্যাপ নেই): ১) "এখনকার জায়গা" (ব্রাউজারের এককালীন GPS, পরে "Google ম্যাপে দেখুন"),
// ২) "Google ম্যাপ থেকে জায়গা দিন" (লিংক পেস্ট; ছোট লিংক সার্ভারে খোলা — maps_link_resolve), ৩) "লোকেশন লিখুন" + এলাকা।
// জায়গা থাকলে জেলা সেই জায়গার, উপজেলা আপনা থেকে (কাছের উপজেলা-কেন্দ্র); না পারলে উপজেলার ঘর থাকে।
import { computed, onMounted, ref, watch } from 'vue';
import { Button, ErrorText, IconLine, TextInput } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import type { AreaValue } from '@/ui/areaFilter';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { areaLabel, currentPosition, districtLabel, ensureAreas, upazila } from '@/lib/geo';
import { MAPS_LINK_RE, googleMapsUrl, parseMapsText, type Point } from './place';
import { withPoint, type LocationValue } from './location';

const props = defineProps<{ value: LocationValue; showErrors?: boolean }>();
const emit = defineEmits<{ change: [v: LocationValue] }>();
const onChange = (v: LocationValue) => emit('change', v);
const picking = ref(false);
const busy = ref<'gps' | 'map' | null>(null);
const err = ref<string | null>(null);
const asking = ref(false);
const mapHelp = ref(false);
onMounted(() => {
  ensureAreas().catch(() => {});
  // আগে থেকে পাওয়া জায়গা (তালিকার GPS) — উপজেলা আপনা থেকে
  const v = props.value;
  if (v.point && v.upazilaId == null) withPoint(v, v.point, v.pointFrom ?? 'gps').then(onChange, () => {});
});

async function locate() {
  asking.value = false;
  busy.value = 'gps';
  err.value = null;
  try {
    const p = await currentPosition();
    onChange(await withPoint(props.value, p, 'gps'));
  } catch {
    err.value = tr("Couldn't get your location — choose the area, or give a place from Google Maps.", 'এখনকার জায়গা পাওয়া যায়নি — এলাকা বেছে নিন, অথবা Google ম্যাপ থেকে জায়গা দিন।');
  } finally {
    busy.value = null;
  }
}

/** লেখার ঘরে Google ম্যাপের লিংক পেস্ট হলে: জায়গা নেওয়া, লিংকটা লেখা থেকে সরানো */
async function fromLink(text: string) {
  const rest = text.replace(MAPS_LINK_RE, '').replace(/\s+/g, ' ').trim();
  const parsed = parseMapsText(text);
  err.value = null;
  if (!parsed) {
    // লেখা যেমন আছে থাকে (মুছে দিলে বা ঠিক করলে আবার দেখা হয়)
    err.value = tr("Couldn't find a place in this link — in Google Maps, press and hold the place, then Share.", 'এই লিংকে জায়গা পাওয়া যায়নি — Google ম্যাপে জায়গাটা চেপে ধরে "শেয়ার" চাপুন।');
    return;
  }
  busy.value = 'map';
  try {
    const p = 'short' in parsed ? await call<Point>('maps_link_resolve', { url: parsed.short }) : parsed;
    onChange(await withPoint({ ...props.value, landmark: rest }, p, 'map'));
    mapHelp.value = false;
  } catch (x) {
    err.value = errorText(x);
  } finally {
    busy.value = null;
  }
}

// লেখার ঘরে লিংক: লেখা থামার ০.৭ সেকেন্ড পরে পড়া — অক্ষর ধরে লিখলে অর্ধেক লিংক থেকে ভুল জায়গা নয়
watch(
  () => props.value.landmark,
  (l, _o, onCleanup) => {
    if (!MAPS_LINK_RE.test(l)) return;
    const h = setTimeout(() => fromLink(l), 700);
    onCleanup(() => clearTimeout(h));
  },
  { immediate: true },
);

const noUpazila = computed(() => props.value.upazilaId == null);
const p = computed(() => props.value.point);
const u = computed(() => upazila(props.value.upazilaId));
function onLandmark(t: string) {
  err.value = null;
  onChange({ ...props.value, landmark: t.slice(0, MAPS_LINK_RE.test(t) ? 600 : 160) });
}
function removePoint() {
  const v = props.value;
  onChange({ ...v, point: null, pointFrom: null, ...(v.upazilaAuto ? { upazilaId: null, upazilaAuto: false } : {}) });
}
function onPicked(v: AreaValue) {
  picking.value = false;
  if (v.upazilaId == null) return;
  // জেলা বদলালে আগের জায়গা আর খাটে না
  const value = props.value;
  const same = v.district === value.district;
  onChange({ ...value, district: v.district, upazilaId: v.upazilaId, unionId: v.unionId ?? null, upazilaAuto: false, point: same ? value.point : null, pointFrom: same ? value.pointFrom : null });
}
</script>

<template>
  <div class="col gap-8 loc-input" data-testid="amb-location">
    <span class="t-title-s">{{ tr('Pick-up from', 'কোথা থেকে নেবে') }}</span>
    <div v-if="p" class="loc-gps-ok row gap-8" :data-testid="value.pointFrom === 'map' ? 'amb-map-ok' : 'amb-gps-ok'">
      <span class="grow col gap-2">
        <span class="t-body-m">{{ value.pointFrom === 'map' ? tr('Place from the map taken ✓', 'ম্যাপের জায়গা নেওয়া হয়েছে ✓') : tr('Current location taken ✓', 'এখনকার জায়গা নেওয়া হয়েছে ✓') }}</span>
        <a class="t-label-l loc-maplink" :href="googleMapsUrl(p)" target="_blank" rel="noopener noreferrer" data-testid="amb-gmaps">{{ tr('See on Google Maps', 'Google ম্যাপে দেখুন') }}</a>
      </span>
      <Button variant="text" small @click="removePoint">{{ tr('Remove', 'বাদ দিন') }}</Button>
    </div>
    <!-- অনুমতি চাওয়ার আগে কেন — ব্রাউজারের নিজের প্রশ্ন এরপর আসে -->
    <div v-else-if="asking" class="loc-why col gap-6">
      <span class="t-body-m">
        {{ tr('Your current location helps the nearest ambulance come first. Only the driver who accepts sees it, and it is deleted 24 hours after the request ends.',
          'এখনকার জায়গা দিলে সবচেয়ে কাছের অ্যাম্বুলেন্স আগে খবর পায়। শুধু রাজি হওয়া চালক দেখবেন, অনুরোধ শেষ হওয়ার ২৪ ঘণ্টা পর মুছে যায়।') }}
      </span>
      <div class="row gap-8">
        <Button small icon="location_on" @click="locate">{{ tr('Allow', 'অনুমতি দিন') }}</Button>
        <Button small variant="text" @click="asking = false">{{ tr('Not now', 'এখন না') }}</Button>
      </div>
    </div>
    <Button v-else icon="location_on" block :busy="busy === 'gps'" data-testid="amb-gps" @click="asking = true">{{ tr('Current location', 'এখনকার জায়গা') }}</Button>
    <template v-if="!p">
      <Button variant="outline" icon="map" block :busy="busy === 'map'" data-testid="amb-map" @click="mapHelp = !mapHelp">{{ tr('Give a place from Google Maps', 'Google ম্যাপ থেকে জায়গা দিন') }}</Button>
      <span v-if="mapHelp" class="t-body-s loc-why" data-testid="amb-map-help">{{ tr('In Google Maps, press and hold the place → Share → Copy link, then paste it in the box below. ', 'Google ম্যাপে জায়গাটা চেপে ধরুন → শেয়ার → লিংক কপি করুন, তারপর নিচের ঘরে পেস্ট করুন। ') }}<a href="https://www.google.com/maps" target="_blank" rel="noopener noreferrer">{{ tr('Open Google Maps', 'Google ম্যাপ খুলুন') }}</a></span>
    </template>
    <TextInput
      :label="p ? tr('Write the location (optional)', 'লোকেশন লিখুন (ঐচ্ছিক)') : tr('Write the location', 'লোকেশন লিখুন')"
      :hint="tr('Area / road / landmark — e.g. house 12, near the school gate. A Google Maps link works too.', 'এলাকা / রাস্তা / চেনা জায়গা — যেমন: বাসা ১২, স্কুল গেটের পাশে। Google ম্যাপের লিংকও দেওয়া যায়।')"
      :model-value="value.landmark"
      maxlength="400"
      :error="showErrors && !p && !value.landmark.trim() ? tr('Required', 'লিখতে হবে') : null"
      data-testid="amb-pickup"
      @update:model-value="onLandmark"
    />
    <button type="button" :class="`loc-area${showErrors && !p && noUpazila ? ' error' : ''}`" data-testid="amb-area" @click="picking = true">
      <IconLine icon="location_on">{{ value.upazilaAuto && u ? `${tr(u.en, u.bn)}, ${districtLabel(value.district)} ${tr('(from the location)', '(জায়গা থেকে)')}` : areaLabel(value.district, value.upazilaId, value.unionId) }}<span v-if="noUpazila && !p" class="loc-need"> · {{ tr('choose upazila', 'উপজেলা বেছে নিন') }}</span></IconLine>
      <span class="t-label-l loc-change">{{ tr('Change', 'বদলান') }}</span>
    </button>
    <ErrorText :text="err" />
    <AreaPicker
      v-if="picking"
      :title="tr('Where to pick up', 'কোথা থেকে নেবে')"
      :value="{ district: value.district, upazilaId: value.upazilaId, unionId: value.unionId }"
      with-unions
      :allow-all-district="false"
      @close="picking = false"
      @picked="onPicked"
    />
  </div>
</template>
