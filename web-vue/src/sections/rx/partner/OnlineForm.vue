<script setup lang="ts">
// অনলাইনে বিক্রির সেটিং: চালু/বন্ধ, কত কিমি, ডেলিভারি চার্জ, নোট
import { ref } from 'vue';
import { RouterLink } from 'vue-router';
import { Button, ErrorText, Switch, TextInput, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { useInvalidate } from './common';
import type { OnlineSettings } from './model';

const props = defineProps<{ start: OnlineSettings; hasLocation: boolean }>();
const invalidate = useInvalidate();
const enabled = ref(props.start.enabled);
const radius = ref(String(props.start.radiusKm || 3));
const fee = ref(props.start.fee);
const note = ref(props.start.note);
const busy = ref(false);
const err = ref<string | null>(null);
async function save() {
  busy.value = true;
  err.value = null;
  try {
    const r = Math.min(30, Math.max(1, parseInt(toAsciiDigits(radius.value), 10) || 3));
    await call('rx_pharmacy_online_set', { enabled: enabled.value, radiusKm: r, fee: fee.value.trim(), note: note.value.trim() });
    toast(tr('Saved', 'সেভ হয়েছে'));
    await invalidate('rx_pharmacy_home');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <form class="col gap-12" @submit.prevent="save">
    <Switch v-model="enabled" :label="tr('Take prescription orders and deliver', 'প্রেসক্রিপশনের অর্ডার নিই, বাসায় পৌঁছে দিই')" />
    <TextInput
      :label="tr('Up to how many km', 'কত কিমি পর্যন্ত')"
      inputmode="numeric"
      :model-value="num(radius)"
      :hint="tr('1 to 30 km', '১ থেকে ৩০ কিমি')"
      @update:model-value="(v) => (radius = toAsciiDigits(v).replace(/\D/g, '').slice(0, 2))"
    />
    <TextInput v-model="fee" :label="tr('Delivery charge (e.g. Tk 30)', 'ডেলিভারি চার্জ (যেমন: ৩০ টাকা)')" maxlength="40" />
    <TextInput v-model="note" :label="tr('Note (e.g. delivery 10 AM – 9 PM)', 'নোট (যেমন: সকাল ১০টা–রাত ৯টা)')" maxlength="120" />
    <p class="t-body-s muted">{{ tr('Orders come only from verified prescriptions. Customers pay you directly — Lifolo takes nothing.', 'অর্ডার আসবে শুধু যাচাই করা প্রেসক্রিপশন থেকে। ক্রেতা টাকা সরাসরি আপনাকে দেবেন — Lifolo কিছু নেয় না।') }}</p>
    <p v-if="!hasLocation" class="t-body-s c-error">
      {{ tr('Add your shop\'s location first (edit your pharmacy).', 'আগে দোকানের লোকেশন দিন (ফার্মেসির তথ্য বদলে)।') }}
      <RouterLink to="/work/pharmacy/edit" class="c-primary">{{ tr('Edit details', 'তথ্য বদলান') }}</RouterLink>
    </p>
    <ErrorText :text="err" />
    <div class="row">
      <span class="grow" />
      <Button type="submit" :busy="busy">{{ tr('Save', 'সেভ করুন') }}</Button>
    </div>
  </form>
</template>
