<script setup lang="ts">
// মেয়াদের শেষ ঘণ্টায়: "খালি আছে — আর ৪৫ মিনিট" আর এক চাপে আবার ১২ ঘণ্টা ("এখনো খালি আছেন?" পুশ এখানেই আনে)।
// পাতা খোলা থাকলেও প্রতি ৩০ সেকেন্ডে মিনিট কমে; মেয়াদ পেরোলে লুকায়। ২০২৬-১০ থেকে "আমার কাজ"-এর অবস্থার বোতামের নিচে (খালি/ট্রিপে এক জায়গায়)।
import { computed } from 'vue';
import { Button, useNow } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';

const props = defineProps<{ a: { status?: string | null; available: boolean; availableUntil?: number | null; availableAt?: number | null }; busy: boolean }>();
const emit = defineEmits<{ renew: [] }>();
const now = useNow(30 * 1000);
/** "খালি আছি"-র মেয়াদ শেষ হতে আর কত মিনিট (এক ঘণ্টার কম হলে), নইলে null — মেয়াদ পেরোলে null */
const left = computed(() => {
  const a = props.a;
  if (a.status !== 'approved' || !a.available) return null;
  const at = a.availableAt ?? 0;
  const until = a.availableUntil || (at > 1 ? at + 12 * 3600 * 1000 : 0);
  const l = until - now.value;
  return l > 0 && l < 3600 * 1000 ? Math.max(1, Math.ceil(l / 60000)) : null;
});
</script>

<template>
  <div v-if="left != null" class="work-still">
    <span class="t-body-m semi grow">{{ tr(`Available — ${left} min left`, `খালি আছে — আর ${num(left)} মিনিট`) }}</span>
    <Button small :busy="busy" @click="emit('renew')">{{ tr('Keep available', 'আবার চালু রাখুন') }}</Button>
  </div>
</template>
