<script setup lang="ts">
// রেটিং (অ্যাপের StarRatingDialog) — অ্যাম্বুলেন্স আর ফার্মেসি।
// ফার্মেসিতে রেটিং দোকানের সেবার — দিকে দিকে তারা: ব্যবহার (সবসময়), ঠিক ওষুধ ও ন্যায্য দাম (কিনলে), ডেলিভারির দ্রুততা (সার্ভার বলে)।
// agreedFare: অ্যাপে ঠিক হওয়া ভাড়া (২.৩০.০) — থাকলে "বলা ভাড়ার চেয়ে বেশি নিয়েছে" বাছা যায়
import { computed, ref } from 'vue';
import { Button, Check, Dialog, ErrorText, TextArea, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { queryClient } from '@/lib/query';
import { taka } from './fare';
import { RATE_ASPECTS, aspectLabel, type Kind, type RateAspect } from './data';
import Stars from './Stars.vue';

const props = withDefaults(defineProps<{ kind?: Kind; id: string; name: string; aspects?: RateAspect[]; agreedFare?: number | null }>(), { kind: 'ambulance' });
const emit = defineEmits<{ close: [] }>();
const RATE: Partial<Record<Kind, [action: string, list: string]>> = { ambulance: ['ambulance_rate', 'ambulances'], pharmacy: ['pharmacy_rate', 'pharmacies'] };
const pharmacy = props.kind === 'pharmacy';
// ব্যবহার সবসময় থাকে; বাকিগুলো সার্ভারের দেওয়া ক্রমে
const shown: RateAspect[] = ['behaviour', ...RATE_ASPECTS.filter((a) => a !== 'behaviour' && props.aspects?.includes(a))];
const stars = ref(0);
const by = ref<Partial<Record<RateAspect, number>>>({});
const comment = ref('');
const over = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);
const ready = computed(() => (pharmacy ? (by.value.behaviour ?? 0) > 0 : stars.value > 0));
async function submit() {
  busy.value = true;
  err.value = null;
  try {
    const [action, list] = RATE[props.kind] ?? RATE.ambulance!;
    const picked = Object.fromEntries(shown.filter((a) => (by.value[a] ?? 0) > 0).map((a) => [a, by.value[a]]));
    await call(action, pharmacy ? { id: props.id, aspects: picked, comment: comment.value.trim() } : { id: props.id, rating: stars.value, comment: comment.value.trim(), ...(props.agreedFare != null ? { overcharged: over.value } : {}) });
    toast(tr('Thanks for the rating!', 'রেটিং দেওয়ার জন্য ধন্যবাদ!'));
    // তালিকা নতুন করে: কার্ডে নতুন গড় (★ ৪.৭ (৩২))
    queryClient.invalidateQueries({ queryKey: [list] });
    emit('close');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="tr('Rate ', 'রেটিং: ') + name" @close="!busy && emit('close')">
    <div v-if="pharmacy" class="col gap-12">
      <p class="t-body-m muted">{{ tr("About the shop's service — not how well the medicine worked", 'দোকানের সেবা নিয়ে — ওষুধ কেমন কাজ করল তা নয়') }}</p>
      <div v-for="a in shown" :key="a" class="col gap-4">
        <span class="t-label-l">{{ aspectLabel(a) }}</span>
        <span v-if="a === 'price'" class="t-body-s muted">{{ tr('Gave what the prescription said, charged a fair price', 'প্রেসক্রিপশনে যা লেখা তা-ই দিয়েছে, দাম ঠিক নিয়েছে') }}</span>
        <Stars :value="by[a] ?? 0" :label="aspectLabel(a)" @change="(n) => (by = { ...by, [a]: n })" />
      </div>
    </div>
    <Stars v-else :value="stars" :label="tr('Rating', 'রেটিং')" @change="(n) => (stars = n)" />
    <Check v-if="agreedFare != null" v-model="over">{{ tr(`Charged more than the agreed fare (${taka(agreedFare)})`, `বলা ভাড়ার চেয়ে বেশি নিয়েছে (${taka(agreedFare)})`) }}</Check>
    <TextArea v-model="comment" :label="tr('Comment (optional)', 'মন্তব্য (ঐচ্ছিক)')" rows="2" maxlength="300" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Later', 'পরে') }}</Button>
      <Button variant="text" :busy="busy" :disabled="!ready" @click="submit">{{ tr('Submit', 'জমা দিন') }}</Button>
    </template>
  </Dialog>
</template>
