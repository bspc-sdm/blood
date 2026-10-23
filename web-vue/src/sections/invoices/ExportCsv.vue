<script setup lang="ts">
// মাসের রসিদ CSV-তে (invoice_export_csv, শুধু মালিক) — হিসাবরক্ষকের জন্য (নিরাপত্তা পর্যালোচনা I15)।
// উত্তর {csv, filename} হলে ফাইল নামানো; {url} হলে সেই লিংক খোলা।
import { ref } from 'vue';
import { Button, ErrorText, Select } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { T } from './text';
import { monthText, shiftMonth, thisMonth, type InvKind } from './model';

const props = defineProps<{ kind: InvKind; bizId: string }>();
const months = [0, -1, -2].map((d) => shiftMonth(thisMonth(), d));
const month = ref(months[0]);
const busy = ref(false);
const err = ref<string | null>(null);
async function run() {
  busy.value = true;
  err.value = null;
  try {
    const r = await call<{ csv?: string; filename?: string; url?: string }>('invoice_export_csv', { kind: props.kind, bizId: props.bizId, month: month.value });
    if (r.url) {
      window.open(r.url, '_blank', 'noopener');
      return;
    }
    // Excel-এ বাংলা ঠিক দেখাতে BOM
    const blob = new Blob(['﻿' + (r.csv ?? '')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = r.filename || `lifolo-${props.kind}-${month.value}.csv`;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="row wrap gap-8 inv-csv">
    <Select v-model="month" :aria-label="T.csvMonth()" :options="months.map((m) => ({ value: m, label: monthText(m) }))" />
    <Button small variant="outline" icon="list_alt" :busy="busy" data-testid="csv-export" @click="run">{{ T.csvExport() }}</Button>
    <ErrorText :text="err" />
  </div>
</template>
