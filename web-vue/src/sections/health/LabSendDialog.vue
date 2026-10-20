<script setup lang="ts">
// বাসা থেকে নমুনা নেয় এমন প্যাথলজিতে পাঠানো: প্যাথলজিতে গিয়ে, অথবা বাসা থেকে (ঠিকানা, ফোন, দিন, সময়, নোট)।
// change: পাঠানো অনুরোধের সময় বদল (প্যাথলজি সময় জানানোর আগে) — শুধু বাসার ঘরগুলো, আগেরটা বসানো। header slot: উপরে ছোট লাইন।
import { computed, ref } from 'vue';
import { Button, ChoiceChips, Dialog, IconLine, TextArea, TextInput } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { todayUtcMidnight } from '@/lib/format';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { SLOT_KEYS, dayWord, homeFeeText, slotPassed, slotText, type LabHome, type Slot } from '@/lib/labprice';
import type { LastHome } from './rx';
import type { HomeReq } from './rxactivity';
import SendError from './SendError.vue';

const props = defineProps<{ title: string; homeFee: number | null; last: LastHome | null; change?: LabHome; onSubmit: (home: HomeReq | null) => Promise<void> }>();
const emit = defineEmits<{ close: [] }>();
const s = useSession();
const care = useCareMode();
const DAYMS = 24 * 60 * 60 * 1000;
const today = todayUtcMidnight();
const change = props.change;
const was = change ? Math.round((change.date - today) / DAYMS) : 1;
const how = ref<'lab' | 'home'>('home');
const address = ref(change?.address ?? props.last?.address ?? '');
const phone = ref(change?.phone || props.last?.phone || (care.value ? '' : s.profile?.phone ?? ''));
// সময় পেরিয়ে যাওয়া অনুরোধ বদলালে কাল থেকে
const day = ref(change && change.status !== 'expired' && was >= 0 && was <= 2 ? was : 1);
const slot = ref<Slot>(change && SLOT_KEYS.includes(change.slot as Slot) ? (change.slot as Slot) : 'early');
const note = ref(change?.note ?? '');
const busy = ref(false);
const err = ref<string | null>(null);
const slots = computed(() => SLOT_KEYS.filter((k) => !slotPassed(day.value, k)));
const home = computed(() => !!change || how.value === 'home');
const howOpts = computed(() => [
  { value: 'home' as const, label: tr('Come and take the sample from home', 'বাসা থেকে নমুনা নিতে আসবে') },
  { value: 'lab' as const, label: tr("I'll go to the lab", 'প্যাথলজিতে গিয়ে দেব') },
]);
const dayOpts = computed(() => [0, 1, 2].map((k) => ({ value: k, label: dayWord(today + k * DAYMS) })));
const slotOpts = computed(() => slots.value.map((k) => ({ value: k, label: slotText(k) })));
async function send() {
  busy.value = true;
  err.value = null;
  try {
    await props.onSubmit(home.value ? { address: address.value.trim(), phone: phone.value.trim(), day: day.value, slot: slots.value.includes(slot.value) ? slot.value : slots.value[0], note: note.value.trim() } : null);
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="change ? tr(`${title} — change the time`, `${title} — সময় বদলান`) : title" @close="!busy && emit('close')">
    <slot name="header" />
    <div v-if="!change" class="health-stack-chips">
      <ChoiceChips v-model="how" :options="howOpts" />
    </div>
    <template v-if="home">
      <IconLine v-if="homeFee != null" icon="payments">{{ homeFeeText(homeFee) }}</IconLine>
      <TextArea v-model="address" :label="tr('Home address', 'বাসার ঠিকানা')" rows="2" maxlength="200" />
      <TextInput v-model="phone" :label="tr('Mobile number', 'মোবাইল নম্বর')" type="tel" inputmode="tel" maxlength="20" />
      <span class="t-label-l">{{ tr('When', 'কবে') }}</span>
      <ChoiceChips v-model="day" :options="dayOpts" />
      <ChoiceChips v-if="slots.length" :model-value="slots.includes(slot) ? slot : slots[0]" :options="slotOpts" @update:model-value="(v: Slot) => (slot = v)" />
      <span v-else class="t-body-s muted">{{ tr('No time left today — choose tomorrow', 'আজ আর সময় নেই — কাল বেছে নিন') }}</span>
      <TextInput v-model="note" :label="tr('Note (optional)', 'নোট (ঐচ্ছিক)')" maxlength="120" :placeholder="tr('e.g. I will be fasting', 'যেমন: খালি পেটে থাকব')" />
      <span class="t-body-s muted">{{ tr('The lab will confirm the time.', 'প্যাথলজি সময় নিশ্চিত করে জানাবে।') }}</span>
    </template>
    <SendError :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" icon="send" :disabled="home && (!address.trim() || !phone.trim() || !slots.length)" @click="send">{{ change ? tr('Change', 'বদলান') : tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
