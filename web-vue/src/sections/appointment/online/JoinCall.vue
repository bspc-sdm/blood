<script setup lang="ts">
// "ভিডিও কলে যুক্ত হোন" — শুধু লিংক (কলের পাতা web-calls শাখার /appointments/:id/call)।
// দেখায় শুধু অনলাইন + নিশ্চিত অ্যাপয়েন্টমেন্টে, স্লটের ১৫ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত (সার্ভারের appt_join-এর মতো)।
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { cx, useNow } from '@/ui/kit';
import { CALL_EARLY_MS, T, apptStartMs, callPath, canJoinCall } from './index';

const props = defineProps<{
  a: { id: string; online: boolean; status: string; date: number; start: string; startAt?: number };
  /** ডাক্তার/সহকারী: ?role=provider */
  provider?: boolean;
  /** অন্যজনের নাম (কলের পাতায় দেখায়) */
  name?: string;
  small?: boolean;
  /** জানালা খোলার আগে "১৫ মিনিট আগে…" লেখা */
  hint?: boolean;
}>();
const now = useNow(20_000);
const open = computed(() => canJoinCall(props.a, now.value));
const before = computed(() => props.hint && props.a.online && props.a.status === 'confirmed' && now.value < apptStartMs(props.a) - CALL_EARLY_MS);
</script>

<template>
  <RouterLink
    v-if="open"
    :to="callPath(a.id, provider, name)"
    :class="cx('btn has-icon appt-join', small && 'small')"
    data-testid="join-call"
  >
    <svg viewBox="0 -960 960 960" aria-hidden="true" focusable="false" fill="currentColor">
      <path d="M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h480q33 0 56.5 23.5T720-720v180l160-160v440L720-420v180q0 33-23.5 56.5T640-160H160Z" />
    </svg>
    {{ T.join() }}
  </RouterLink>
  <p v-else-if="before" class="t-body-s muted" data-testid="join-later">{{ T.joinLater() }}</p>
</template>
