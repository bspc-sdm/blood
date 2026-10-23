<script setup lang="ts">
// রসিদ দেখা আর ছাপা: কাগজ (A4 / ৮০ / ৫৮ মিমি), অঙ্ক (বাংলা/ইংরেজি), "প্রিন্ট / PDF"।
// ওয়েবে ব্রাউজারের প্রিন্ট (সেখানে "PDF হিসেবে সেভ" — প্রেসক্রিপশনের মতো); ডেস্কটপে অ্যাপের প্রিন্টার (থার্মাল বা A4)।
// ছাপার কপি পাতার শেষে (body) লুকানো থাকে — .health-print-এর নিয়মে শুধু ওটাই ছাপা হয়।
import { ref } from 'vue';
import { Button, ErrorText, Segmented, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import Receipt from './Receipt.vue';
import { T } from './text';
import { T as OT } from '@/offline/text';
import { digitPref, paperPref, printInvoice } from './print';
import type { Invoice } from './model';

defineProps<{ inv: Invoice }>();
defineSlots<{ actions?: () => unknown }>();
const busy = ref(false);
const err = ref<string | null>(null);
async function print(inv: Invoice) {
  busy.value = true;
  err.value = null;
  try {
    if ((await printInvoice(inv, paperPref.value)) === 'desktop') toast(T.printed());
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="col gap-12 inv-view">
    <div class="row wrap gap-8 inv-tools">
      <Segmented
        v-model="paperPref"
        :label="T.paper()"
        :options="[{ value: 'A4', label: 'A4' }, { value: '80', label: OT.mm80() }, { value: '58', label: OT.mm58() }]"
      />
      <Segmented v-model="digitPref" :label="T.digits()" :options="[{ value: 'bn', label: '১২৩' }, { value: 'en', label: '123' }]" />
      <span class="grow" />
      <slot name="actions" />
      <Button icon="list_alt" variant="tonal" :busy="busy" data-testid="receipt-print" @click="print(inv)">{{ T.printPdf() }}</Button>
    </div>
    <ErrorText :text="err" />
    <div class="inv-paper-wrap">
      <Receipt :inv="inv" :paper="paperPref" :digits="digitPref" />
    </div>
    <Teleport to="body">
      <div class="health-print inv-print" aria-hidden="true"><Receipt :inv="inv" :paper="paperPref" :digits="digitPref" /></div>
    </Teleport>
  </div>
</template>
