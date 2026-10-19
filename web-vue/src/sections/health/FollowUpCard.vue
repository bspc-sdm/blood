<script setup lang="ts">
// আবার দেখানো: এক চাপে সেই চেম্বারে সিরিয়াল
import { computed, ref } from 'vue';
import { Button, ErrorText, InfoText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { queryClient } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { formatDate, todayUtcMidnight } from '@/lib/format';
import type { Rx } from './rx';

const props = defineProps<{ rx: Rx }>();
const busy = ref(false);
const err = ref<string | null>(null);
const info = ref<string | null>(null);
const book = computed(() => props.rx.followUpBookDate);
const show = computed(() => !(props.rx.status === 'cancelled' || book.value == null || props.rx.chamberIndex == null || book.value < todayUtcMidnight()));
async function go() {
  busy.value = true;
  err.value = null;
  try {
    await call('book', { providerUid: props.rx.doctorUid, chamberIndex: props.rx.chamberIndex, date: book.value, note: props.rx.followUpNote || tr('Follow-up', 'ফলো-আপ') });
    info.value = tr("Request sent. You'll get a notification when the doctor confirms.", 'অনুরোধ গেছে। ডাক্তার নিশ্চিত করলে নোটিফিকেশন পাবেন।');
    queryClient.invalidateQueries({ queryKey: ['my_appointments'] });
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
</script>

<template>
  <div v-if="show" class="card tonal col">
    <span class="t-title-m">{{ tr('See the doctor again', 'আবার ডাক্তার দেখানো') }}</span>
    <span class="t-body-m">{{ formatDate(book!) + (rx.doctor?.chamber?.name ? ' · ' + rx.doctor.chamber.name : '') }}</span>
    <ErrorText :text="err" />
    <InfoText :text="info" />
    <div>
      <Button square icon="calendar_month" :busy="busy" :disabled="!!info" @click="go">{{ tr('Get a serial', 'সিরিয়াল নিন') }}</Button>
    </div>
  </div>
</template>
