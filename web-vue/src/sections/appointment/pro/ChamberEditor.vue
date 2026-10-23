<script setup lang="ts">
// একটা চেম্বারের সম্পাদনা
import { Check, Icon, IconButton, Segmented, Switch, TextInput } from '@/ui/kit';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { chamberArea, chamberLabel, slotsOf, withSlots, type Chamber } from '../model';
import { T, bkashInput, hasPhone, normBkash, type ChamberMode } from '../online';
import TimeSlotsEditor from './TimeSlotsEditor.vue';

// canOnline: ডাক্তার — "চেম্বারে" / "অনলাইন (ভিডিও)" বাছাই; অনলাইনে হাসপাতাল/ঠিকানা/রুম নেই, bKash নম্বর আর নোট আছে
// origBkash: সেভ করা bKash নম্বর — বদলালে "নতুন নম্বর ২৪ ঘণ্টা পর চালু হবে" (সার্ভারের নিয়ম)
const props = defineProps<{ c: Chamber; canOnline?: boolean; origBkash?: string | null }>();
const emit = defineEmits<{ change: [c: Chamber]; changeHospital: []; district: []; remove: [] }>();
const upd = (patch: Partial<Chamber>) => emit('change', { ...props.c, ...patch });
function setMode(m: ChamberMode) {
  if (m === props.c.mode) return;
  // অনলাইনে অ্যাপয়েন্টমেন্ট অ্যাপেই (ভিডিও কল অ্যাপে) — সবসময় চালু
  upd(m === 'online' ? { mode: m, appointments: true } : { mode: m });
  // চেম্বারে ফিরলে হাসপাতাল না থাকলে বেছে নিতে হবে
  if (m === 'in_person' && !props.c.custom && !props.c.hospitalId) emit('changeHospital');
}
const bkashErr = () => {
  const d = bkashInput(props.c.bkashNumber).replace(/^\+?88(?=01)/, '');
  return d.length >= 11 && !normBkash(d) ? T.errBkash() : null;
};
const bkashHint = () => {
  const now = normBkash(props.c.bkashNumber);
  return props.origBkash && now && now !== normBkash(props.origBkash) ? T.bkashLater() : T.bkashHint();
};
function setPatientMin(v: string) {
  const n = parseInt(toAsciiDigits(v).replace(/[^0-9]/g, '').slice(0, 3), 10);
  upd({ patientMin: Number.isFinite(n) && n > 0 ? Math.min(n, 120) : null });
}
</script>

<template>
  <div class="card col gap-12 appt-ch-ed" :data-mode="c.mode">
    <div v-if="canOnline" class="col gap-6">
      <span class="t-label-l">{{ T.chamberType() }}</span>
      <Segmented
        :label="T.chamberType()"
        :options="[{ value: 'in_person', label: T.modeLabel('in_person') }, { value: 'online', label: T.modeLabel('online') }]"
        :model-value="c.mode"
        @update:model-value="setMode"
      />
    </div>
    <div v-if="c.mode === 'online'" class="row top">
      <div class="col gap-2 grow">
        <span class="t-title-s">{{ T.modeLabel('online') }}</span>
        <span class="t-body-s muted">{{ T.videoLine() }}</span>
      </div>
      <IconButton icon="delete" :label="tr('Remove', 'সরান')" @click="emit('remove')" />
    </div>
    <template v-else-if="c.custom">
      <div class="row top">
        <div class="grow">
          <TextInput
            :label="tr('Place name', 'জায়গার নাম')"
            :placeholder="tr('e.g. Fit Life Gym', 'যেমন: ফিট লাইফ জিম')"
            :model-value="c.hospitalName"
            @update:model-value="(v) => upd({ hospitalName: v.slice(0, 100), hospitalNameBn: v.slice(0, 100) })"
          />
        </div>
        <IconButton icon="delete" :label="tr('Remove', 'সরান')" :style="{ marginTop: '20px' }" @click="emit('remove')" />
      </div>
      <button type="button" class="area-btn" @click="emit('district')">
        <Icon name="location_on" />
        <span>{{ c.district ? areaLabel(c.district, c.upazilaId) : tr('Choose district', 'জেলা বেছে নিন') }}</span>
        <Icon name="expand_more_round" />
      </button>
      <TextInput :label="tr('Address', 'ঠিকানা')" :placeholder="tr('House, road, area', 'বাড়ি, রোড, এলাকা')" :model-value="c.address" @update:model-value="(v) => upd({ address: v.slice(0, 150) })" />
    </template>
    <div v-else class="row top">
      <div class="col gap-2 grow">
        <span class="t-title-s">{{ c.hospitalId ? chamberLabel(c) : T.chooseHospital() }}</span>
        <span v-if="chamberArea(c)" class="t-body-s muted">{{ chamberArea(c) }}</span>
      </div>
      <IconButton icon="edit" :label="tr('Change hospital', 'হাসপাতাল বদলান')" @click="emit('changeHospital')" />
      <IconButton icon="delete" :label="tr('Remove', 'সরান')" @click="emit('remove')" />
    </div>
    <TimeSlotsEditor :slots="slotsOf(c)" @change="(l) => emit('change', withSlots(c, l))" />
    <TextInput :label="tr('Note (optional)', 'নোট (ঐচ্ছিক)')" :placeholder="tr('e.g. Closed on public holidays', 'যেমন: সরকারি ছুটির দিন বন্ধ')" :model-value="c.schedule" @update:model-value="(v) => upd({ schedule: v.slice(0, 120) })" />
    <div class="appt-two">
      <TextInput v-if="c.mode !== 'online'" :label="tr('Room (optional)', 'রুম (ঐচ্ছিক)')" :model-value="c.room" @update:model-value="(v) => upd({ room: v.slice(0, 60) })" />
      <TextInput :label="tr('Fee', 'ফি')" :placeholder="tr('e.g. 500 Tk', 'যেমন: ৫০০ টাকা')" :disabled="c.noFee" :model-value="c.fee" @update:model-value="(v) => upd({ fee: v.slice(0, 40) })" />
    </div>
    <Check :model-value="c.noFee" @update:model-value="(v) => upd({ noFee: v, fee: v ? '' : c.fee })">{{ tr('No fee', 'ফি চাই না') }}</Check>
    <template v-if="c.mode === 'online'">
      <TextInput
        v-if="!c.noFee"
        :label="T.bkash()"
        :hint="bkashHint()"
        :error="bkashErr()"
        inputmode="tel"
        autocomplete="off"
        placeholder="01XXXXXXXXX"
        data-testid="bkash-number"
        :model-value="c.bkashNumber"
        @update:model-value="(v) => upd({ bkashNumber: bkashInput(v) })"
      />
      <TextInput
        :label="T.noteLabel()"
        :hint="T.noteHint()"
        :error="hasPhone(c.note) ? T.errNotePhone() : null"
        :placeholder="T.notePh()"
        data-testid="online-note" :model-value="c.note" @update:model-value="(v) => upd({ note: v.slice(0, 200) })" />
    </template>
    <TextInput :label="tr('Serial phone (optional)', 'সিরিয়ালের ফোন (ঐচ্ছিক)')" inputmode="tel" :model-value="c.serialPhone" @update:model-value="(v) => upd({ serialPhone: v.replace(/[^0-9+]/g, '').slice(0, 15) })" />
    <Switch v-if="c.mode !== 'online'" :label="tr('Appointments in app at this chamber', 'এই চেম্বারে অ্যাপে অ্যাপয়েন্টমেন্ট')" :model-value="c.appointments" @update:model-value="(v) => upd({ appointments: v })" />
    <TextInput
      v-if="c.appointments"
      :label="tr('Avg. time per patient (min)', 'রোগীপ্রতি গড় সময় (মিনিট)')"
      inputmode="numeric"
      :placeholder="num(10)"
      :model-value="c.patientMin ? num(c.patientMin) : ''"
      @update:model-value="setPatientMin"
    />
  </div>
</template>
