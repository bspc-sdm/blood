<script setup lang="ts">
import { computed, ref } from 'vue';
import { Button, ChoiceChips, ErrorText, Icon, Switch, TextArea, TextInput } from '@/ui/kit';
import PlacePicker from '@/ui/PlacePicker.vue';
import FormPage from '@/shell/FormPage.vue';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { districtLabel, geohash, roundCoord } from '@/lib/geo';
import { useSession, type Place } from '@/lib/session';
import { BLOOD_GROUPS, MAX_OPEN_REQUESTS, dhakaMillis } from '@/lib/rules';
import { normalizePhone, todayUtcMidnight } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { api, myRequests, useInvalidate } from '../data';
import { isActive, nameCase } from '../model';
import FieldLabel from './FieldLabel.vue';
import BagsPicker from './BagsPicker.vue';
import DateTimeFields from './DateTimeFields.vue';
import TravelFareSwitch from './TravelFareSwitch.vue';
import HospitalField from './HospitalField.vue';

defineProps<{ title: string }>();
const s = useSession();
const nav = useNav();
const invalidate = useInvalidate();
const profile = s.profile!;
const group = ref('');
const bags = ref(1);
const hospital = ref('');
const note = ref('');
const neededBy = ref(todayUtcMidnight());
const hour = ref<number | null>(null);
const minute = ref<number | null>(null);
const urgent = ref(false);
const travelFare = ref(false);
const phone = ref(profile.phone);
// প্রাইভেসি: রিকোয়েস্ট সবাই দেখে, তাই বাসার এলাকা আপনা থেকে বসানো হয় না
const place = ref<Place | null>(null);
const picking = ref(false);
const busy = ref(false);
const error = ref<string | null>(null);
const groupOpts = BLOOD_GROUPS.map((g) => ({ value: g, label: g }));
const hospDistrict = computed(() => place.value?.placeName ?? profile.placeName);

async function submit() {
  const uid = s.uid;
  const contact = normalizePhone(phone.value);
  const err = !uid
    ? tr('Please log in again', 'আবার লগইন করুন')
    : !group.value
      ? tr("Choose the patient's blood group", 'রোগীর রক্তের গ্রুপ বেছে নিন')
      : !hospital.value.trim()
        ? tr('Enter the hospital', 'হাসপাতালের নাম লিখুন')
        : !place.value
          ? tr('Choose the area', 'এলাকা বেছে নিন')
          : neededBy.value < todayUtcMidnight()
            ? tr('Pick today or a later date', 'আজ বা পরের কোনো তারিখ দিন')
            : hour.value == null || minute.value == null
              ? tr('Pick the time', 'সময় বেছে নিন')
              : dhakaMillis(neededBy.value, hour.value, minute.value) <= Date.now()
                ? tr('That time has passed', 'এই সময় পার হয়ে গেছে')
                : !contact
                  ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
                  : null;
  error.value = err;
  const pl = place.value;
  if (err || !uid || !pl || hour.value == null || minute.value == null || !contact) return;
  busy.value = true;
  try {
    const open = (await myRequests(uid)).filter((r) => isActive(r)).length;
    if (open >= MAX_OPEN_REQUESTS) {
      throw new Error(tr(`You can keep up to ${num(MAX_OPEN_REQUESTS)} open requests`, `একসাথে সর্বোচ্চ ${num(MAX_OPEN_REQUESTS)}টা খোলা রিকোয়েস্ট রাখা যায়`));
    }
    const lat = roundCoord(pl.lat);
    const lng = roundCoord(pl.lng);
    const id = await api.create({
      requesterName: profile.name.slice(0, 80),
      requesterPhone: contact,
      patientGroup: group.value,
      bags: bags.value,
      hospital: nameCase(hospital.value).slice(0, 120),
      placeName: pl.placeName,
      lat,
      lng,
      geohash: geohash(lat, lng),
      neededByMillis: neededBy.value,
      neededAtMillis: dhakaMillis(neededBy.value, hour.value, minute.value),
      urgent: urgent.value,
      travelFare: travelFare.value,
      note: note.value.trim().slice(0, 300),
    });
    // আশেপাশের ডোনারদের জানানো; না গেলে রিকোয়েস্টের পাতায় আবার চেষ্টার বাটন থাকে
    await api.notify(id).catch(() => {});
    await invalidate();
    nav(`/my-requests/${id}`, { replace: true });
  } catch (e) {
    error.value = errorText(e) || tr("Couldn't create the request", 'রিকোয়েস্ট দেওয়া যায়নি');
  } finally {
    busy.value = false;
  }
}
function onPicked(p: Place) {
  place.value = p;
  picking.value = false;
}
function onTime(h: number, m: number) {
  hour.value = h;
  minute.value = m;
}
</script>

<template>
  <FormPage :title="title" back-to="/">
    <FieldLabel>{{ tr("Patient's blood group", 'রোগীর রক্তের গ্রুপ') }}</FieldLabel>
    <div class="blood-groups">
      <ChoiceChips :options="groupOpts" :model-value="group || null" @update:model-value="(g: string) => (group = g)" />
    </div>

    <FieldLabel>{{ tr('Bags needed', 'কত ব্যাগ') }}</FieldLabel>
    <BagsPicker v-model="bags" />

    <HospitalField v-model="hospital" :district="hospDistrict" />

    <button type="button" class="btn outline has-icon block" @click="picking = true">
      <Icon name="location_on" />
      <span class="ellipsis">{{ place ? districtLabel(place.placeName) : tr('Choose area', 'এলাকা বেছে নিন') }}</span>
    </button>

    <FieldLabel>{{ tr('Needed by', 'কবে লাগবে') }}</FieldLabel>
    <DateTimeFields :date="neededBy" :hour="hour" :minute="minute" @date="(d: number) => (neededBy = d)" @time="onTime" />

    <Switch v-model="urgent" :label="tr('Urgent', 'জরুরি')" />
    <TravelFareSwitch v-model="travelFare" />

    <TextArea :label="tr('Note (optional)', 'অতিরিক্ত তথ্য (ঐচ্ছিক)')" :model-value="note" maxlength="300" rows="3" @update:model-value="(v: string) => (note = v.slice(0, 300))" />

    <TextInput
      v-model="phone"
      :label="tr('Contact number', 'যোগাযোগ নম্বর')"
      :hint="tr('Donors who agreed call this number — it can be a family member\'s.', 'রাজি ডোনাররা এই নম্বরে ফোন করবেন — রোগীর লোকের নম্বরও দিতে পারেন।')"
      inputmode="tel"
      autocomplete="tel"
      maxlength="20"
    />

    <ErrorText :text="error" />
    <Button block icon="send" :busy="busy" @click="submit">{{ title }}</Button>

    <PlacePicker v-if="picking" :title="tr('Where is the patient?', 'রোগী কোথায় আছেন?')" @picked="onPicked" @close="picking = false" />
  </FormPage>
</template>
