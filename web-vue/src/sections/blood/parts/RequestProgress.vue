<script setup lang="ts">
import type { BloodRequest, DonorResponse } from '../model';
import AgreedLine from './AgreedLine.vue';
import BagRow from './BagRow.vue';

defineProps<{ r: BloodRequest; list: DonorResponse[] }>();
</script>

<template>
  <div class="col gap-8">
    <AgreedLine :list="list" />
    <BagRow :r="r" :list="list" />
  </div>
</template>
