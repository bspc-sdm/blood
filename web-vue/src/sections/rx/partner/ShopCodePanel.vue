<script setup lang="ts">
// কাউন্টারের QR আর দোকানের কোড — পোস্টার ছাপা (/shop/{কোড})
import { ref } from 'vue';
import { Button } from '@/ui/kit';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import type { PharmacyHome } from './model';
import Panel from './Panel.vue';

const home = useCall<PharmacyHome>('rx_pharmacy_home', {});
const qrOk = ref(true);
const poster = (code: string) => window.open(`/shop/${encodeURIComponent(code)}`, '_blank', 'noopener');
</script>

<template>
  <Panel v-if="home.data" :title="tr('Shop QR & code', 'দোকানের QR ও কোড')" icon="share">
    <div class="rxp-shop">
      <!-- QR-এর নিচেই কোড — স্ক্যান না হলে ক্রেতা লিখে পাঠাতে পারেন -->
      <div class="col rxp-qr-box">
        <img v-if="qrOk" class="rxp-qr" :src="`/api/share?shopqr=${encodeURIComponent(home.data.shopCode)}`" :alt="tr('Shop QR', 'দোকানের QR')" @error="qrOk = false" />
        <span class="t-label-s muted">{{ tr('Code', 'কোড') }}</span>
        <span class="rxp-shop-code num">{{ home.data.shopCode }}</span>
      </div>
      <div class="col gap-6">
        <Button variant="tonal" small icon="share" @click="poster(home.data.shopCode)">{{ tr('Print counter poster', 'কাউন্টারের পোস্টার প্রিন্ট') }}</Button>
      </div>
    </div>
    <p class="t-body-s muted">
      {{ tr('Put the poster at the counter. When a customer scans the QR in the app or types this code or your shop name, the prescription comes straight to "New" under Orders.', 'পোস্টারটা কাউন্টারে লাগিয়ে রাখুন। ক্রেতা অ্যাপে QR স্ক্যান করলে বা কোড/দোকানের নাম লিখলে প্রেসক্রিপশন সাথে সাথে "অর্ডার" ট্যাবের "নতুন"-এ আসবে।') }}
    </p>
  </Panel>
</template>
