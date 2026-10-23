<script setup lang="ts">
// রাজি হওয়া এক ডোনার: নাম, দূরত্ব, ফোন, নিশ্চিত / বাদ
import { computed, ref } from 'vue';
import { Action, BloodGroupBadge, ConfirmDialog, PhoneLine, Tag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { needsFare, type DonorResponse } from '../model';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';

const props = withDefaults(defineProps<{ d: DonorResponse; active: boolean; busy: boolean; canConfirm?: boolean; reqId?: string }>(), { canConfirm: true });
const emit = defineEmits<{ confirm: []; remove: [] }>();
const askRemove = ref(false);
const fare = computed(() => needsFare(props.d));
const meta = computed(() =>
  [props.d.distanceKm != null ? num(props.d.distanceKm) + tr(' km away', ' কিমি দূরে') : null, fare.value ? tr('needs travel cost', 'যাতায়াত খরচ লাগবে') : null].filter(Boolean).join(' · '),
);
function doRemove() {
  askRemove.value = false;
  emit('remove');
}
</script>

<template>
  <div class="col gap-6 blood-donor">
    <div class="row" :style="{ gap: '10px' }">
      <BloodGroupBadge :group="d.donorGroup" :size="36" />
      <div class="col gap-2 grow">
        <div class="row gap-8">
          <span class="semi ellipsis">{{ d.donorName }}</span>
          <Tag v-if="d.status === 'donated'" tone="good">{{ tr('Donated', 'রক্ত দিয়েছেন') }}</Tag>
          <Tag v-else-if="d.confirmed" tone="good">{{ tr('Confirmed', 'নিশ্চিত') }}</Tag>
        </div>
        <span v-if="meta" :class="['t-body-s', fare ? 'c-primary' : 'muted']">{{ meta }}</span>
      </div>
    </div>
    <div class="blood-indent"><PhoneLine :phone="d.donorPhone" /></div>
    <!-- অ্যাপে কল: রিকোয়েস্ট খোলা আর ডোনার রাজি থাকলে (ফোনই আগে) -->
    <div v-if="reqId" class="blood-indent"><ContactActions :message="chatOn()" @message="openChatAt" ctx="blood" :ref-id="reqId" :peer-uid="d.donorUid" :peer-name="d.donorName" :phone="d.donorPhone" :show="active && d.status === 'accepted'" /></div>
    <div v-if="active && d.status === 'accepted'" class="row gap-8 blood-indent">
      <Action v-if="!d.confirmed && canConfirm" icon="check_circle" :busy="busy" @click="emit('confirm')">{{ tr('Confirm', 'নিশ্চিত করুন') }}</Action>
      <Action icon="close" :disabled="busy" @click="askRemove = true">{{ tr('Remove', 'বাদ দিন') }}</Action>
    </div>
    <ConfirmDialog
      v-if="askRemove"
      :title="tr(`Remove ${d.donorName}?`, `${d.donorName}-কে বাদ দেবেন?`)"
      :text="tr(
        'They\'ll be told they don\'t need to come. If it\'s a mistake, you can bring them back within 30 minutes.',
        'তাঁকে জানানো হবে যে আর আসতে হবে না। ভুল হলে ৩০ মিনিটের মধ্যে ফিরিয়ে আনতে পারবেন।',
      )"
      :confirm="tr('Remove', 'বাদ দিন')"
      danger
      @close="askRemove = false"
      @confirm="doRemove"
    />
  </div>
</template>
