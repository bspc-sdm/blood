<script setup lang="ts">
// জরুরি সতর্কতা (৯৯৯)
import { ref } from 'vue';
import { Icon, IconButton } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { store } from '@/lib/store';

const props = withDefaults(defineProps<{ k?: string }>(), { k: 'health' });
const key = `lf.dismissed.999_${props.k}`;
const hidden = ref(store.get(key) === '1');
function hide() {
  store.set(key, '1');
  hidden.value = true;
}
</script>

<template>
  <div v-if="!hidden" class="card tonal row top appt-notice">
    <Icon name="info" />
    <p class="t-body-s grow">{{ tr('Emergency? Call 999 now. Answers here are general guidance, not a diagnosis.', 'জরুরি অবস্থায় এখনই ৯৯৯-এ ফোন করুন। এখানের উত্তর সাধারণ পরামর্শ, রোগ নির্ণয় নয়।') }}</p>
    <IconButton icon="close" small :label="tr('Hide', 'সরান')" @click="hide" />
  </div>
</template>
