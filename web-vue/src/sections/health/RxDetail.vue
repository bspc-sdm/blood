<script setup lang="ts">
// একটা প্রেসক্রিপশন: কাগজ, পাঠানো, আবার দেখানো; ছাপার কপি body-তে (শুধু কাগজটাই ছাপা হয়)
import { computed } from 'vue';
import { ErrorBox, Loading } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import type { Rx } from './rx';
import RxActivitySection from './RxActivitySection.vue';
import RxPaper from './RxPaper.vue';
import FollowUpCard from './FollowUpCard.vue';
import PrintButton from './PrintButton.vue';

const props = defineProps<{ id: string; initial: Rx | null; listLoading: boolean; back: () => void }>();
const lang = useLang();
const care = useCareMode();
const s = useSession();
// তালিকায় না থাকলে (সরাসরি লিংক) সার্ভার থেকে
const r = useQ<Rx>(() => ({
  queryKey: ['rx_get', props.id, care.value?.uid ?? null, lang.value, s.uid],
  queryFn: () => call<{ item: Rx }>('rx_get', { id: props.id }).then((x) => x.item),
  enabled: !props.initial && !props.listLoading,
}));
const rx = computed(() => props.initial ?? r.data ?? null);
</script>

<template>
  <DetailPane :title="tr('Prescription', 'প্রেসক্রিপশন')" :on-back="back">
    <template v-if="rx" #actions><PrintButton /></template>
    <template v-if="rx">
      <!-- কাগজ RxActivitySection-এর ভেতরে: উপরে পাঠানোর বাটন, তারপর কাগজ -->
      <RxActivitySection :rx="rx">
        <template #paper><RxPaper :rx="rx" /></template>
      </RxActivitySection>
      <FollowUpCard :rx="rx" />
      <Teleport to="body">
        <div class="health-print" aria-hidden="true"><RxPaper :rx="rx" print /></div>
      </Teleport>
    </template>
    <Loading v-else-if="listLoading || r.isPending" />
    <ErrorBox v-else :error="errorText(r.error)" :on-retry="() => r.refetch()" />
  </DetailPane>
</template>
