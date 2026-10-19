<script setup lang="ts">
// ৯৯৯ (অ্যাপের EmergencyCallCard: ছোট এক লাইন, ✕ চাপলে আর আসে না)
import { ref } from 'vue';
import { Icon, IconButton } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { store } from '@/lib/store';

const DISMISS_999 = 'lf.dismissed.999_ambulance';
const hidden = ref(store.get(DISMISS_999) === '1');
function hide() {
  store.set(DISMISS_999, '1');
  hidden.value = true;
}
</script>

<template>
  <div v-if="!hidden" class="services-999">
    <span class="t-body-s muted grow">{{ tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা') }}</span>
    <a class="act" href="tel:999">
      <Icon name="call" />
      <span>{{ num('999') }}</span>
    </a>
    <IconButton small icon="close" :label="tr('Hide', 'সরান')" @click="hide" />
  </div>
</template>
