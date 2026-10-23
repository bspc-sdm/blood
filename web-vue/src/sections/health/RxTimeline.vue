<script setup lang="ts">
// প্রেসক্রিপশনের ভেতরে উপরে: এখন কোন অবস্থায় (চিপ) + কী কী হয়েছে, তারিখসহ (লেখা, ফার্মেসিতে পাঠানো, গ্রহণ, কেনা, ল্যাব, রিপোর্ট,
// বাতিল, ফলো-আপ) — rx_get-এর state.timeline (backend/docs/RX-STATE.md); অ্যাপের RxTimelineCard
import { computed } from 'vue';
import { call } from '@/lib/api';
import { useQ } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import type { Rx } from './rx';
import { stepDate, stepText } from './rxstate';
import RxStatus from './RxStatus.vue';

const props = defineProps<{ rx: Rx }>();
const lang = useLang();
const care = useCareMode();
const s = useSession();
// RxDetail-এর একই চাবি — সরাসরি লিংকে খোলা হলে একবারই আনা হয়
const q = useQ<Rx>(() => ({
  queryKey: ['rx_get', props.rx.id, care.value?.uid ?? null, lang.value, s.uid],
  queryFn: () => call<{ item: Rx }>('rx_get', { id: props.rx.id }).then((x) => x.item),
}));
const full = computed(() => q.data ?? props.rx);
const steps = computed(() => full.value.state?.timeline ?? []);
</script>

<template>
  <section v-if="full.state" class="card rx-timeline" data-testid="rx-timeline" :aria-label="tr('Status', 'অবস্থা')">
    <div class="t-title-s">{{ tr('Where it is now', 'এখন কোন অবস্থায়') }}</div> <!-- bn-ok -->
    <RxStatus :rx="full" />
    <ol v-if="steps.length" class="steps">
      <li v-for="(st, i) in steps" :key="i" :class="[st.ahead && 'ahead', st.kind === 'cancelled' && 'bad']">
        <span class="dot" aria-hidden="true" />
        <span class="what">{{ stepText(st) }}</span>
        <span class="when">{{ stepDate(st) }}</span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.rx-timeline { display: flex; flex-direction: column; gap: 4px; }
.rx-timeline :deep(.rx-status) { margin-top: 4px; }
.steps { list-style: none; margin: 10px 0 0; padding: 0; display: flex; flex-direction: column; }
.steps li {
  position: relative; display: grid; grid-template-columns: 16px 1fr auto; column-gap: 8px; align-items: baseline;
  padding: 0 0 10px; font-size: 14px; line-height: 20px; color: var(--on-surface);
}
.steps li::before {
  /* দাগ: এক ধাপ থেকে পরেরটায় */
  content: ''; position: absolute; left: 4px; top: 14px; bottom: -2px; width: 2px; background: var(--outline-variant);
}
.steps li:last-child { padding-bottom: 0; }
.steps li:last-child::before { display: none; }
.dot { width: 10px; height: 10px; border-radius: 50%; background: var(--primary); align-self: start; margin-top: 5px; position: relative; z-index: 1; }
.what { min-width: 0; overflow-wrap: anywhere; }
.when { color: var(--on-surface-variant); font-size: 12px; white-space: nowrap; }
.ahead .dot { background: transparent; box-shadow: inset 0 0 0 2px var(--primary); }
.ahead .what { color: var(--on-surface-variant); }
.bad .dot { background: var(--error); }
</style>
