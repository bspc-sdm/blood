<script setup lang="ts">
// ১, ২, ৩… করে প্রতিটা ব্যাগ — যতগুলো পূরণ হয়েছে সেগুলো ভরাট
import { computed } from 'vue';
import { BloodBag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { counts, willing, type BloodRequest, type DonorResponse } from '../model';

const props = defineProps<{ r: BloodRequest; list: DonorResponse[] }>();
const done = computed(() => Math.min(Math.max(willing(props.list).filter(counts).length, props.r.bagsReceived), props.r.bags));
</script>

<template>
  <div class="row gap-8 blood-bags">
    <BloodBag
      v-for="i in r.bags"
      :key="i - 1"
      :n="i"
      :filled="i <= done"
      :label="i <= done ? tr(`Bag ${i} arranged`, `${num(i)} নম্বর ব্যাগ পূরণ`) : tr(`Bag ${i} needed`, `${num(i)} নম্বর ব্যাগ বাকি`)"
    />
  </div>
</template>
