<script setup lang="ts">
// সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট: রোগী নিজে — কেয়ারগিভার দেখেন না (দেখাতে চাইলে কেয়ারগিভারের পাতায়, care_share_psych);
// কেয়ারগিভার মোডে (রোগী দেখাতে দিয়েছেন বলেই দেখা যাচ্ছে) — সেটাই লেখা। pro_psychologist বন্ধ থাকলে কিছুই নয়।
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { IconLine } from '@/ui/kit';
import { useCareMode } from '@/lib/care';
import { T, psychOn } from '@/lib/practice';
import type { Appointment } from '../appointment/model';

const props = defineProps<{ a: Appointment }>();
const care = useCareMode();
const show = computed(() => psychOn() && props.a.type === 'psychologist' && props.a.role === 'patient');
</script>

<template>
  <template v-if="show">
    <IconLine v-if="care" icon="privacy_tip" data-testid="psych-shared-note">{{ T.sharedByPatient() }}</IconLine>
    <div v-else class="col gap-4" data-testid="psych-private-note">
      <IconLine icon="privacy_tip">{{ T.privateNote() }}</IconLine>
      <RouterLink class="t-body-s c-primary" to="/care">{{ T.privateLink() }}</RouterLink>
    </div>
  </template>
</template>
