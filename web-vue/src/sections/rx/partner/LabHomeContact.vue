<script setup lang="ts">
// বাসা থেকে নমুনা — ঠিকানা, ফোন, নোট (সার্ভার শুধু চালু অনুরোধে পাঠায়)
import { computed } from 'vue';
import { Icon } from '@/ui/kit';
import type { LabOrder } from './model';

const props = defineProps<{ o: LabOrder }>();
const h = computed(() => {
  const h = props.o.home;
  const fromHome = !!h && h.status !== 'declined';
  if (!h || !fromHome || !['sent', 'received'].includes(props.o.status) || !(h.address || h.phone || h.note)) return null;
  return h;
});
</script>

<template>
  <div v-if="h" class="col gap-2" @click.stop>
    <span v-if="h.address" class="icon-line"><Icon name="location_on" /><span>{{ h.address }}</span></span>
    <span v-if="h.phone" class="icon-line"><Icon name="call" /><a class="rxp-phone num" :href="`tel:${h.phone}`">{{ h.phone }}</a></span>
    <span v-if="h.note" class="icon-line"><Icon name="edit_note" /><span>{{ h.note }}</span></span>
  </div>
</template>
