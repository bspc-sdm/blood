<script setup lang="ts">
// ফোন নম্বর: লেখা হিসেবে দেখা যায় (কপি করা যায়), পাশে "ফোন করুন" (মোবাইলে tel: খোলে)
import { ref } from 'vue';
import { num, tr } from '@/lib/i18n';
import Icon from './Icon.vue';

const props = defineProps<{ phone: string; onCall?: () => void; big?: boolean }>();
const copied = ref(false);
function copy() {
  navigator.clipboard?.writeText(props.phone).then(() => (copied.value = true), () => (copied.value = false));
  setTimeout(() => (copied.value = false), 2000);
}
</script>

<template>
  <div class="row wrap gap-8">
    <a :class="['btn square has-icon', !big && 'small']" :href="`tel:${phone}`" @click="onCall?.()">
      <Icon name="call" />{{ tr('Call', 'ফোন করুন') }}
    </a>
    <span class="t-title-m num" :style="{ userSelect: 'all', fontWeight: 600 }">{{ num(phone) }}</span>
    <button type="button" class="btn text small" @click="copy">{{ copied ? tr('Copied', 'কপি হয়েছে') : tr('Copy', 'কপি') }}</button>
  </div>
</template>
