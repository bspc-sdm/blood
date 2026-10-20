<script setup lang="ts">
// তালিকার উপরে: "অ্যাম্বুলেন্স ডাকুন" (বড় ভরাট) + পাশে "ফোন" (সবচেয়ে কাছের খালি গাড়ি)
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';

defineProps<{ nearest?: { phone: string } | null; nearestSub?: string | null; onCallNearest?: () => void }>();
const emit = defineEmits<{ request: [] }>();
</script>

<template>
  <div class="col gap-6">
    <div class="amb-callbar">
      <button type="button" class="services-nearest amb-call-main" data-testid="amb-request" @click="emit('request')">
        <span class="ic"><Icon name="ambulance" /></span>
        <span class="col gap-2 grow">
          <span class="t-title-m">{{ tr('Call an ambulance', 'অ্যাম্বুলেন্স ডাকুন') }}</span>
          <span class="t-body-s sub">{{ tr('All free ones nearby at once', 'কাছের খালি গাড়িগুলোতে একসাথে') }}</span>
        </span>
      </button>
      <a v-if="nearest" class="btn outline square has-icon amb-call-phone" :href="`tel:${nearest.phone}`" @click="onCallNearest?.()">
        <Icon name="call" />{{ tr('Call', 'ফোন') }}
      </a>
    </div>
    <span v-if="nearest && nearestSub" class="t-body-s muted">{{ tr('Call: nearest free — ', 'ফোন: সবচেয়ে কাছের খালি — ') + nearestSub }}</span>
  </div>
</template>
