<script setup lang="ts">
// TODO(health): পুরো রূপ health অংশের সাথে আসছে — এখন শুধু বাটন
import { Button } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { LastHome, PlaceLab, PlacePharmacy, Rx } from './rx';

defineProps<{ lab?: PlaceLab; pharmacy?: PlacePharmacy; mode?: 'shop' | 'online'; rx?: Rx | null; last?: LastHome | null; card?: boolean }>();
</script>

<template>
  <span v-if="card" class="contents" @click.stop @keydown.stop>
    <Button icon="send" square class="ph-card-send" data-testid="ph-card-send">{{ tr('Send prescription', 'প্রেসক্রিপশন পাঠান') }}</Button>
  </span>
  <Button v-else icon="send" class="detail-bar-main">{{ tr('Send prescription', 'প্রেসক্রিপশন পাঠান') }}</Button>
</template>
