<script setup lang="ts">
// রিপোর্ট আসার পর: সিরিয়াল নিয়ে দেখানো, অথবা অনলাইনে (ফি দিয়ে)
import { computed } from 'vue';
import { Action } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import type { Rx } from './rx';
import { payLabel, reviewStatus, type RxReview } from './rxactivity';

const props = defineProps<{ rx: Rx; r: RxReview | null; nextOpen: number | null; busy: boolean }>();
const emit = defineEmits<{ pay: [r: RxReview]; serial: [d: number] }>();
const live = computed(() => !!(props.r && props.r.status && props.r.status !== 'off'));
</script>

<template>
  <div class="col gap-6">
    <template v-if="live && r">
      <span class="t-body-m c-primary">{{ reviewStatus(r.status) }}</span>
      <template v-if="r.status === 'unpaid'">
        <span class="t-body-m">{{ tr(`Show online: fee ${r.fee}. Send to ${r.payNumber} (`, `অনলাইনে দেখান: ফি ${r.fee}। ${num(r.payNumber)} নম্বরে (`) + r.payMethods.map(payLabel).join(', ') + tr('), then enter the TrxID.', ') পাঠিয়ে TrxID দিন।') }}</span>
        <span v-if="r.rejectNote" class="t-body-s c-error">{{ tr("Doctor's note: ", 'ডাক্তারের নোট: ') + r.rejectNote }}</span>
        <div><Action primary icon="payments" @click="emit('pay', r)">{{ tr("I've sent the money", 'টাকা পাঠিয়েছি') }}</Action></div>
      </template>
      <div v-if="r.status === 'done' && r.comment" class="card tonal col gap-2">
        <span class="t-label-l semi">{{ r.doctorName }}</span>
        <p class="t-body-m pre">{{ r.comment }}</p>
      </div>
    </template>
    <div v-if="nextOpen != null && rx.chamberIndex != null && r?.status !== 'done'">
      <Action icon="calendar_month" :busy="busy" @click="emit('serial', nextOpen)">{{ tr('Get a serial to show it (', 'দেখানোর সিরিয়াল নিন (') + formatDate(nextOpen) + ')' }}</Action>
    </div>
  </div>
</template>
