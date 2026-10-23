<script setup lang="ts">
// আসার সময়: কার্ডের মতো গড়ন (ছবি, নাম, মূল লাইন, বাটন) — তথ্য এলে জায়গা নড়ে না
withDefaults(defineProps<{ n?: number }>(), { n: 4 });
</script>

<template>
  <div class="lst-list" aria-busy="true" data-testid="listing-skeleton">
    <div v-for="i in n" :key="i" class="card lst-card lst-skel" aria-hidden="true">
      <div class="lst-head">
        <span class="skeleton lst-skel-av" />
        <div class="lst-main">
          <span class="skeleton lst-skel-line w60" />
          <span class="skeleton lst-skel-line w80 sm" />
        </div>
      </div>
      <div class="lst-meta"><span class="skeleton lst-skel-line w30 sm" /></div>
      <div class="lst-actions"><span class="skeleton lst-skel-btn" /><span class="grow" /><span class="skeleton lst-skel-ic" /></div>
    </div>
  </div>
</template>
