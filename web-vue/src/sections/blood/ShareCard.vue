<script setup lang="ts">
// সবার জন্য: কেউ রাজি হয়েছেন কি না, আর পরিচিতদের পাঠানোর বাটন
import { computed } from 'vue';
import { Button } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { BloodRequest } from './model';
import DonorStatusTag from './parts/DonorStatusTag.vue';

const props = defineProps<{ r: BloodRequest }>();
const emit = defineEmits<{ share: [] }>();
const noDonor = computed(() => props.r.acceptedCount === 0 && !props.r.fulfilled);
</script>

<template>
  <section :class="noDonor ? 'card tonal col' : 'card col'">
    <DonorStatusTag :r="r" />
    <p class="t-body-m">
      {{ noDonor
        ? tr('No one has agreed yet. Know someone with a matching group, even far away? Send it to them.', 'এখনো কেউ রাজি হননি। মিলে যায় এমন গ্রুপের কাউকে চেনেন, দূরে হলেও? তাঁকে পাঠান।')
        : tr('Know someone who could help? Share it.', 'সাহায্য করতে পারেন এমন কাউকে চেনেন? শেয়ার করুন।') }}
    </p>
    <Button variant="tonal" block icon="share" @click="emit('share')">{{ tr('Send to people you know', 'পরিচিতদের পাঠান') }}</Button>
  </section>
</template>
