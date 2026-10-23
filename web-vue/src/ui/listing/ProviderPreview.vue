<script setup lang="ts">
// "আমার কাজ"-এর উপরে: "রোগীরা আপনাকে যেভাবে দেখেন" — সেবার তালিকার একই কার্ড (ListingCard) আর একই তথ্য
// (openNow, nextSlot, stock, ratingSummary, ব্যাজ, "পুরনো তথ্য" …)। ভাঁজ করা যায় (এই ফোন/ব্রাউজারে ব্যবহারকারী ধরে মনে থাকে);
// কার্ডে চাপলে রোগীরা যে বিস্তারিত দেখেন সেটা খোলে। নিচে (slot) এক চাপের সুইচ — বদলালে কার্ড সাথে সাথে বদলায়।
import { computed, ref, watch } from 'vue';
import { Icon } from '@/ui/kit';
import { store } from '@/lib/store';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import ListingCard from './ListingCard.vue';
import ListingSkeleton from './ListingSkeleton.vue';
import { LT, type Listing } from './model';

const props = defineProps<{ l: Listing | null; loading?: boolean; publicTo?: string | null; storeKey: string; title?: string }>();
const s = useSession();
const nav = useNav();
const key = computed(() => `lf.pv.closed.${s.uid ?? 'guest'}.${props.storeKey}`);
const closed = ref(store.get(key.value) === '1');
watch(key, (k) => (closed.value = store.get(k) === '1'));
function toggle() {
  closed.value = !closed.value;
  store.set(key.value, closed.value ? '1' : null);
}
</script>

<template>
  <section class="pv-box" data-testid="provider-preview">
    <button type="button" class="pv-head" :aria-expanded="!closed" data-testid="preview-toggle" @click="toggle">
      <Icon name="person_outline" />
      <span class="t-title-s grow">{{ title ?? LT.previewTitle() }}</span>
      <Icon name="expand_more_round" :class="!closed && 'flip'" />
    </button>
    <template v-if="!closed">
      <ListingSkeleton v-if="loading && !l" :n="1" />
      <ListingCard v-else-if="l" :l="l" preview :on-open="publicTo ? () => nav(publicTo!) : undefined" />
    </template>
    <div v-if="$slots.default" class="pv-switches" data-testid="quick-switches"><slot /></div>
  </section>
</template>
