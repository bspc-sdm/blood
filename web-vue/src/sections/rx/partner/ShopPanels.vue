<script setup lang="ts">
// ফার্মেসির "দোকান" ট্যাবের (/work/pharmacy/shop) কাউন্টারের অংশ — দোকানের QR ও কোড, অনলাইনে বিক্রি, চেয়েও পাননি (২.২৭.০)।
// আগে "প্রেসক্রিপশন ও অর্ডার"-এর পাশে ছিল; এখন অর্ডারের পাতায় শুধু অর্ডার। আলাদা ভাগে আসে (defineAsyncComponent)।
import ShopCodePanel from './ShopCodePanel.vue';
import OnlineSettingsPanel from './OnlineSettingsPanel.vue';
import MissedPanel from './MissedPanel.vue';
</script>

<template>
  <div class="col gap-16">
    <ShopCodePanel />
    <OnlineSettingsPanel />
    <MissedPanel />
  </div>
</template>
