<script setup lang="ts">
// রোগীর "কোথায় যাবেন" (২.৩১.০): বোতাম চাপলে হাসপাতাল/ক্লিনিকের তালিকা (জেলারটা আগে, কাছেরটা আগে; দেশের বড়গুলো;
// খোঁজা) আর "অন্য জায়গা লিখুন"। বাছা থাকলে নাম আর "বদলান"।
import { onMounted, ref } from 'vue';
import { IconLine } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { ensureAreas } from '@/lib/geo';
import { destLabel, fromFacility, type Dest } from './fare';
import type { Point } from './place';
import FacilityDialog from './FacilityDialog.vue';

defineProps<{ label: string; district: string; near?: Point | null; value: Dest | null; error?: string | null }>();
const emit = defineEmits<{ change: [d: Dest | null] }>();
const open = ref(false);
onMounted(() => ensureAreas().catch(() => {}));
</script>

<template>
  <div class="col gap-6">
    <span class="t-title-s">{{ label }}</span>
    <button type="button" :class="`loc-area${error ? ' error' : ''}`" data-testid="amb-dest-pick" @click="open = true">
      <IconLine icon="local_hospital"><template v-if="value">{{ destLabel(value) }}</template><span v-else class="muted">{{ tr('Choose a hospital or clinic…', 'হাসপাতাল বা ক্লিনিক বেছে নিন…') }}</span></IconLine>
      <span class="t-label-l loc-change">{{ value ? tr('Change', 'বদলান') : tr('Choose', 'বাছুন') }}</span>
    </button>
    <span v-if="error" class="t-body-s loc-need">{{ error }}</span>
    <FacilityDialog
      v-if="open"
      :title="label"
      :district="district"
      :near="near"
      :on-other="(t: string) => { open = false; emit('change', { kind: 'hospital', name: t }); }"
      @close="open = false"
      @pick="(f) => { open = false; emit('change', fromFacility(f)); }"
    />
  </div>
</template>
