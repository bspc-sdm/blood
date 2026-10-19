<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { BloodGroupBadge, Button, ErrorText, Switch, TextArea, TextInput } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { districtLabel } from '@/lib/geo';
import { dhakaMillis } from '@/lib/rules';
import { dhakaHourMinute, normalizePhone, todayUtcMidnight } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { api, requesterContact, useInvalidate } from '../data';
import { nameCase, type BloodRequest } from '../model';
import FieldLabel from './FieldLabel.vue';
import BagsPicker from './BagsPicker.vue';
import DateTimeFields from './DateTimeFields.vue';
import TravelFareSwitch from './TravelFareSwitch.vue';
import HospitalField from './HospitalField.vue';

const props = defineProps<{ title: string; back: string; r: BloodRequest }>();
const nav = useNav();
const invalidate = useInvalidate();
const r = props.r;
const initial = r.neededAtMillis != null ? dhakaHourMinute(r.neededAtMillis) : null;
const bags = ref(r.bags);
const hospital = ref(r.hospital);
const note = ref(r.note);
const neededBy = ref(r.neededByMillis);
const hour = ref<number | null>(initial?.[0] ?? null);
const minute = ref<number | null>(initial?.[1] ?? null);
const urgent = ref(r.urgent);
const travelFare = ref(r.travelFare);
const savedPhone = ref<string | null>(null);
const phone = ref('');
const busy = ref(false);
const error = ref<string | null>(null);

// যোগাযোগ নম্বর (রাজি ডোনাররা এতে ফোন করেন)
onMounted(() => {
  requesterContact(r.id).then((c) => {
    if (c) {
      savedPhone.value = c.phone;
      phone.value = c.phone;
    }
  }, () => {});
});

async function save() {
  const at = hour.value != null && minute.value != null ? dhakaMillis(neededBy.value, hour.value, minute.value) : null;
  const newPhone = phone.value.trim() && phone.value.trim() !== savedPhone.value ? phone.value.trim() : null;
  const err = !hospital.value.trim()
    ? tr('Enter the hospital', 'হাসপাতালের নাম লিখুন')
    : neededBy.value < todayUtcMidnight()
      ? tr('Pick today or a later date', 'আজ বা পরের কোনো তারিখ দিন')
      : at == null
        ? tr('Pick the time', 'সময় বেছে নিন')
        : at !== r.neededAtMillis && at <= Date.now()
          ? tr('That time has passed', 'এই সময় পার হয়ে গেছে')
          : newPhone && !normalizePhone(newPhone)
            ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
            : null;
  error.value = err;
  if (err || at == null) return;
  busy.value = true;
  try {
    const n = await api.edit({
      requestId: r.id,
      bags: bags.value,
      hospital: nameCase(hospital.value),
      neededByMillis: neededBy.value,
      neededAtMillis: at,
      urgent: urgent.value,
      travelFare: travelFare.value,
      note: note.value.trim(),
      ...(newPhone ? { contactPhone: normalizePhone(newPhone) } : {}),
    });
    await invalidate();
    const info = n > 0 ? tr(`Saved. ${num(n)} donor(s) were notified.`, `সেভ হয়েছে। রাজি থাকা ${num(n)} জন ডোনারকে জানানো হয়েছে।`) : tr('Saved.', 'সেভ হয়েছে।');
    nav(props.back, { replace: true, state: { info } });
  } catch (e) {
    error.value = errorText(e) || tr("Couldn't save", 'সেভ হয়নি');
  } finally {
    busy.value = false;
  }
}
function onTime(h: number, m: number) {
  hour.value = h;
  minute.value = m;
}
</script>

<template>
  <FormPage :title="title" :back-to="back">
    <div class="row gap-12">
      <BloodGroupBadge :group="r.patientGroup" :size="44" />
      <div class="col gap-2">
        <span class="semi">{{ districtLabel(r.placeName) }}</span>
        <span class="t-body-m muted">{{ tr("Group and area can't be changed", 'গ্রুপ আর এলাকা বদলানো যায় না') }}</span>
      </div>
    </div>

    <FieldLabel>{{ tr('Bags needed', 'কত ব্যাগ') }}</FieldLabel>
    <BagsPicker v-model="bags" />

    <HospitalField v-model="hospital" :district="r.placeName || null" />

    <FieldLabel>{{ tr('Needed by', 'কবে লাগবে') }}</FieldLabel>
    <DateTimeFields :date="neededBy" :hour="hour" :minute="minute" @date="(d: number) => (neededBy = d)" @time="onTime" />

    <Switch v-model="urgent" :label="tr('Urgent', 'জরুরি')" />
    <TravelFareSwitch v-model="travelFare" />

    <TextArea :label="tr('Note (optional)', 'অতিরিক্ত তথ্য (ঐচ্ছিক)')" :model-value="note" maxlength="300" rows="3" @update:model-value="(v: string) => (note = v.slice(0, 300))" />

    <TextInput
      v-if="savedPhone != null"
      v-model="phone"
      :label="tr('Contact number', 'যোগাযোগ নম্বর')"
      :hint="tr('Donors who agreed call this number — it can be a family member\'s.', 'রাজি ডোনাররা এই নম্বরে ফোন করবেন — রোগীর লোকের নম্বরও দিতে পারেন।')"
      inputmode="tel"
      maxlength="20"
    />

    <ErrorText :text="error" />
    <Button block :busy="busy" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
  </FormPage>
</template>
