<script setup lang="ts">
// "আবার জানান": মোট ৩ বার, দুইবারের মাঝে ৬ ঘণ্টা
import { computed } from 'vue';
import { Action } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { bagsLeft, isActive, type BloodRequest } from './model';
import SlotRow from './parts/SlotRow.vue';

const props = defineProps<{ r: BloodRequest; busy: boolean; disabled: boolean }>();
const emit = defineEmits<{ notifyAgain: [] }>();
const show = computed(() => !(!isActive(props.r) || bagsLeft(props.r) <= 0 || props.r.notifyRounds < 1 || props.r.notifyRounds > 2));
const waitMs = computed(() => props.r.lastNotifiedMillis + 6 * 3600 * 1000 - Date.now());
</script>

<template>
  <SlotRow v-if="show">
    <span v-if="waitMs > 0" class="t-body-s muted">
      {{ tr(
        `${num(bagsLeft(r))} bag(s) still needed · you can notify more donors in ${num(Math.ceil(waitMs / 3600000))}h`,
        `আরও ${num(bagsLeft(r))} ব্যাগ লাগবে · ${num(Math.ceil(waitMs / 3600000))} ঘণ্টা পর আরও ডোনারকে জানাতে পারবেন`,
      ) }}
    </span>
    <Action v-else icon="notifications" primary :busy="busy" :disabled="disabled" @click="emit('notifyAgain')">{{ tr('Notify', 'জানান') }}</Action>
  </SlotRow>
</template>
