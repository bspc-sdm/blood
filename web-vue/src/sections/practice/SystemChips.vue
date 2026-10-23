<script setup lang="ts">
// রোগীর ছাঁকনি: সব / এলোপ্যাথি / হোমিওপ্যাথি / ইউনানি / আয়ুর্বেদ (practice_systems; সুইচ বন্ধ থাকলে ডাকা জায়গা দেখায় না)
import { num, tr } from '@/lib/i18n';
import { SYSTEMS, T, systemLabel } from '@/lib/practice';

// counts: সার্ভারের facets.systems ({code, n}) — এই এলাকায় কোন পদ্ধতির কতজন (না থাকলে শুধু নাম)
const props = defineProps<{ value: string | null; counts?: { code: string; n: number }[] | null }>();
const n = (s: string) => props.counts?.find((x) => x.code === s)?.n;
const emit = defineEmits<{ change: [v: string | null] }>();
</script>

<template>
  <div class="chips scroll practice-sys" role="group" :aria-label="T.system()" data-testid="system-chips">
    <button type="button" class="chip" :aria-pressed="!value" @click="emit('change', null)">{{ tr('All', 'সব') }}</button>
    <button v-for="s in SYSTEMS" :key="s" type="button" class="chip" :aria-pressed="value === s" @click="emit('change', s)">{{ systemLabel(s) + (n(s) != null ? ` (${num(n(s)!)})` : '') }}</button>
  </div>
</template>
