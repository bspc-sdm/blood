<script setup lang="ts">
// প্রবেশপথের বোতাম (অর্ডার, পরীক্ষা, অ্যাম্বুলেন্স, রসিদের বাকি, চেম্বারের অগ্রিম): পেমেন্ট থাকলে তার অবস্থা আর "পেমেন্ট করুন" /
// "পেমেন্ট দেখুন"; না থাকলে start দিয়ে শুরু। কোন সুইচে দেখাবে তা ডাকার জায়গা ঠিক করে (payOn)।
import { computed, ref } from 'vue';
import { Button, Tag } from '@/ui/kit';
import type { PayLink, PayRequest } from './model';
import { T, statusText, statusTone } from './text';
import PayDialog from './PayDialog.vue';

const props = withDefaults(
  defineProps<{ link: PayLink | null; start?: (() => Promise<PayRequest | null>) | null; label?: string; note?: string | null; small?: boolean }>(),
  { start: null, label: undefined, note: null, small: true },
);
const emit = defineEmits<{ changed: [req: PayRequest | null] }>();
const open = ref(false);
const st = computed(() => props.link?.request?.status ?? props.link?.status ?? null);
const done = computed(() => st.value === 'confirmed' || st.value === 'refunded' || st.value === 'expired' || st.value === 'cancelled');
const show = computed(() => !!props.link || !!props.start);
</script>

<template>
  <div v-if="show" class="row wrap gap-6 pay-entry" data-testid="pay-entry" :data-status="st ?? 'none'">
    <Tag v-if="st && st !== 'open'" :tone="statusTone(st)" data-testid="pay-entry-state">{{ statusText(st) }}</Tag>
    <Button :small="small" :variant="done || st === 'claimed' ? 'text' : 'tonal'" icon="payments" data-testid="pay-open" @click="open = true">
      {{ done || st === 'claimed' ? T.seePayment() : label ?? T.payNow() }}
    </Button>
    <span v-if="note && !done" class="t-body-s muted">{{ note }}</span>
    <PayDialog v-if="open" :id="link?.id ?? null" :initial="link?.request ?? null" :start="link ? null : start" @close="open = false" @changed="(r) => emit('changed', r)" />
  </div>
</template>
