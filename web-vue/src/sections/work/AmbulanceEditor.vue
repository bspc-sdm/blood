<script setup lang="ts">
// অ্যাম্বুলেন্স যোগ/বদল
import { ref } from 'vue';
import { Button, ChoiceChips, ConfirmDialog, ErrorText, Field, InfoText, TextInput } from '@/ui/kit';
import type { AreaValue } from '@/ui/areaFilter';
import { useSession } from '@/lib/session';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { InaccuratePosition, precisePosition } from '@/lib/geo';
import { useNav } from '@/lib/nav';
import { chartDraft, chartFromDraft } from '@/sections/services/fare';
import FareChartEditor from '@/sections/services/FareChartEditor.vue';
import { PAPERS_CHANGED, ambulanceDocs, isContactPhone, missingDocs, phoneInput, sentForApproval, useAfterChange, useCatalog, type Ambulance } from './shared';
import AreaField from './AreaField.vue';
import DocPhotosField from './DocPhotosField.vue';
import StatusTag from './StatusTag.vue';
import StatusReason from './StatusReason.vue';
import TermsCheck from './TermsCheck.vue';

const props = defineProps<{ existing: Ambulance | null }>();
const emit = defineEmits<{ done: [flash: string] }>();
const s = useSession();
const nav = useNav();
const cat = useCatalog('ambulanceType');
const after = useAfterChange();
const ex = props.existing;
const p = s.profile;
const name = ref(ex?.name ?? '');
const phone = ref(ex?.phone ?? p?.phone ?? '');
const vehicleNo = ref(ex?.vehicleNo ?? '');
const types = ref<string[]>(ex?.types ?? ['general']);
const place = ref<AreaValue | null>(ex ? { district: ex.district, upazilaId: ex.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
const area = ref(ex?.area ?? '');
const fare = ref(ex?.fare ?? '');
const chart = ref(chartDraft(ex?.fareChart));
// গাড়ি যেখানে অপেক্ষা করে (ঐচ্ছিক): "সবচেয়ে কাছের" খুঁজতে; না দিলে জেলার কেন্দ্র ধরা হয়।
// শুধু ঠিকঠাক GPS (২০০ মিটারের ভেতরে) নেওয়া হয়; ভুল হলে এই সারিতেই লেখা, ফর্ম সেভ আটকায় না। clearBase: "জায়গা মুছুন"
const base = ref<{ lat: number; lng: number } | null>(null);
const clearBase = ref(false);
const gps = ref(false);
const gpsErr = ref<string | null>(null);
const docs = ref<Record<string, string>>({});
const agreed = ref(!!ex);
const busy = ref(false);
const err = ref<string | null>(null);
const del = ref(false);
const delErr = ref<string | null>(null);

async function useGps() {
  gps.value = true;
  gpsErr.value = null;
  try {
    const pos = await precisePosition(200);
    base.value = { lat: pos.lat, lng: pos.lng };
    clearBase.value = false;
  } catch (x) {
    gpsErr.value =
      x instanceof InaccuratePosition
        ? tr("Couldn't get an accurate location — go outside and try again.", 'লোকেশন ঠিকমতো পাওয়া যায়নি — বাইরে গিয়ে আবার চেষ্টা করুন')
        : tr("Couldn't get your location. Allow location in the browser.", 'লোকেশন পাওয়া যায়নি। ব্রাউজারে লোকেশনের অনুমতি দিন।');
  } finally {
    gps.value = false;
  }
}
function forgetBase() {
  base.value = null;
  gpsErr.value = null;
  // আগে দেওয়া জায়গা থাকলে সেভের সময় সার্ভারেও মোছে
  clearBase.value = !!ex?.hasBase;
}

async function save() {
  const specs = ambulanceDocs();
  const e = !name.value.trim() ? tr('Enter the service or driver name', 'সার্ভিস বা চালকের নাম লিখুন')
    : !isContactPhone(phone.value) ? tr('Enter a valid phone number', 'সঠিক ফোন নম্বর দিন')
      : !vehicleNo.value.trim() ? tr('Enter the vehicle registration number', 'গাড়ির রেজিস্ট্রেশন নম্বর দিন')
        : !types.value.length ? tr('Choose the ambulance type', 'অ্যাম্বুলেন্সের ধরন বেছে নিন')
          : !place.value ? tr('Choose a district', 'জেলা বেছে নিন')
            : !ex && missingDocs(specs, docs.value) ? missingDocs(specs, docs.value)
              : !agreed.value ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
  const fc = chartFromDraft(chart.value);
  const e2 = e ?? ('error' in fc ? fc.error : null);
  err.value = e2;
  if (e2 || !place.value || 'error' in fc) return;
  busy.value = true;
  try {
    const r = await call<{ pending?: boolean; baseIgnored?: boolean }>('ambulance_save', {
      id: ex?.id ?? null, name: name.value.trim(), phone: phone.value, vehicleNo: vehicleNo.value.trim(), types: types.value, district: place.value.district, upazilaId: place.value.upazilaId ?? null,
      area: area.value.trim(), fare: fare.value.trim(), fareChart: fc.chart, termsAccepted: true, docs: docs.value,
      ...(base.value ? { lat: base.value.lat, lng: base.value.lng } : clearBase.value ? { clearBase: true } : {}),
    });
    after();
    // জায়গাটা জেলার বাইরে হলে সার্ভার শুধু জায়গাটা বাদ দেয়, বাকিটা সেভ হয়
    const ignored = r.baseIgnored ? tr(' — vehicle location was outside the district, not saved', ' — গাড়ির জায়গা জেলার বাইরে, তাই নেওয়া হয়নি') : '';
    emit('done', (r.pending ? sentForApproval('ambulance') : tr('Saved', 'সেভ হয়েছে')) + ignored);
  } catch (x) {
    err.value = errorText(x);
    busy.value = false;
  }
}
async function remove() {
  if (!ex) return;
  busy.value = true;
  delErr.value = null;
  try {
    await call('ambulance_delete', { id: ex.id });
    after();
    emit('done', tr('Removed', 'মুছে ফেলা হয়েছে'));
  } catch (x) {
    delErr.value = errorText(x);
    busy.value = false;
  }
}
</script>

<template>
  <div v-if="existing" class="row wrap gap-8">
    <StatusTag :status="existing.status" />
    <StatusReason :status="existing.status" :reason="existing.statusReason" />
  </div>
  <TextInput v-model="name" :label="tr('Service / driver name', 'সার্ভিস বা চালকের নাম')" maxlength="80" autocapitalize="words" />
  <div class="work-two">
    <TextInput :label="tr('Phone for calls', 'যে নম্বরে ফোন আসবে')" :hint="tr('Mobile, landline or hotline', 'মোবাইল, ল্যান্ডলাইন বা হটলাইন')" inputmode="tel" :model-value="phone" @update:model-value="(v) => (phone = phoneInput(v))" />
    <TextInput v-model="vehicleNo" :label="tr('Vehicle registration no.', 'গাড়ির রেজিস্ট্রেশন নম্বর')" maxlength="40" />
  </div>
  <Field :label="tr('Type', 'ধরন')">
    <ChoiceChips v-model="types" multi :options="cat.options.value" />
  </Field>
  <AreaField :label="tr('Base area', 'কোথা থেকে চলে')" :value="place" @change="(v) => (place = v)" />
  <Field :label="tr('Where the vehicle waits (stand / hospital gate) — not your home', 'গাড়ি যেখানে অপেক্ষা করে (স্ট্যান্ড/হাসপাতাল গেট) — বাসা নয়')">
    <div class="col gap-6">
      <InfoText v-if="base" :text="tr('Vehicle location set', 'গাড়ির জায়গা নেওয়া হয়েছে')" />
      <span v-else class="t-body-s muted">
        {{
          clearBase ? tr('Location will be removed when you save.', 'সেভ করলে জায়গা মুছে যাবে।')
          : existing?.hasBase ? tr('Location given. Update only if the waiting place changed.', 'জায়গা দেওয়া আছে। অপেক্ষার জায়গা বদলালে তবেই আবার দিন।')
            : tr('Optional — stand there and tap. Nearby patients find you first; only the area (about 1 km) is kept.', 'ঐচ্ছিক — সেখানে দাঁড়িয়ে চাপুন। কাছের রোগী আগে খুঁজে পাবেন; শুধু এলাকা (প্রায় ১ কিমি) রাখা হয়।')
        }}
      </span>
      <div class="row wrap gap-8">
        <Button v-if="!base" variant="outline" icon="location_on" :busy="gps" @click="useGps">{{ tr('Use current location', 'বর্তমান লোকেশন ধরুন') }}</Button>
        <Button v-if="base || (existing?.hasBase && !clearBase)" variant="text" icon="close" :disabled="gps" @click="forgetBase">{{ tr('Remove location', 'জায়গা মুছুন') }}</Button>
      </div>
      <ErrorText :text="gpsErr" />
    </div>
  </Field>
  <TextInput v-model="area" :label="tr('Where you go (optional)', 'কোন কোন এলাকায় যান (ঐচ্ছিক)')" maxlength="120" />
  <FareChartEditor :district="place?.district ?? null" :upazila-id="place?.upazilaId ?? null" :value="chart" @change="(d) => (chart = d)" />
  <TextInput v-model="fare" :label="tr('Fare note (optional)', 'ভাড়া নিয়ে নোট (ঐচ্ছিক)')" :placeholder="tr('e.g. Within city 800 Tk, per km 40 Tk', 'যেমন: শহরের মধ্যে ৮০০ টাকা, প্রতি কিমি ৪০ টাকা')" maxlength="120" />
  <DocPhotosField :specs="ambulanceDocs()" :photos="docs" :note="existing ? PAPERS_CHANGED() : null" @change="(d) => (docs = d)" />
  <TermsCheck v-model="agreed" />
  <ErrorText :text="err" />
  <div class="form-actions">
    <Button v-if="existing && existing.status !== 'suspended'" variant="danger-text" class="work-del" :disabled="busy" @click="del = true">{{ tr('Remove this ambulance', 'এই অ্যাম্বুলেন্স সরিয়ে দিন') }}</Button>
    <Button variant="text" :disabled="busy" @click="nav('/work/ambulance')">{{ tr('Cancel', 'বাতিল') }}</Button>
    <Button :busy="busy" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
  </div>
  <ConfirmDialog
    v-if="del && existing"
    :title="tr('Remove this ambulance?', 'এই অ্যাম্বুলেন্স সরিয়ে দেবেন?')"
    :text="`${existing.name} · ${existing.vehicleNo}`"
    :confirm="tr('Remove', 'মুছে ফেলুন')"
    danger
    :busy="busy"
    :error="delErr"
    @confirm="remove"
    @close="del = false"
  />
</template>
