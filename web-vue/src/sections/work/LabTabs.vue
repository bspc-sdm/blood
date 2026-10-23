<script setup lang="ts">
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { T } from '../invoices/text';
import { myBusinessTitle, useNewCount, type LabTab } from './shared';

const props = defineProps<{ active: LabTab }>();
const nav = useNav();
const s = useSession();
const approved = computed(() => (s.work.lab as { status?: string } | null)?.status === 'approved');
const fresh = useNewCount('lab_orders', approved);
const top = computed(() => (props.active === 'orders' ? 'orders' : props.active === 'bill' || props.active === 'invoices' ? 'bill' : 'lab'));
</script>

<template>
  <div v-if="approved" class="col gap-8 work-tabs">
    <PillTabs
      :label="myBusinessTitle('lab')"
      :value="top"
      :tabs="[
        { key: 'orders', label: tr('Requests', 'অনুরোধ'), icon: 'list_alt', count: fresh },
        { key: 'bill', label: T.labBill(), icon: 'edit_note' },
        { key: 'lab', label: tr('Lab', 'ল্যাব'), icon: 'local_hospital' },
      ]"
      @change="(k) => nav(k === 'orders' ? '/work/lab/orders' : k === 'bill' ? '/work/lab/orders/bill' : '/work/lab/info')"
    />
    <PillTabs
      v-if="top === 'bill'"
      secondary
      :label="T.labBill()"
      :value="active"
      :tabs="[
        { key: 'bill', label: T.newBill(), icon: 'edit_note' },
        { key: 'invoices', label: T.receipts(), icon: 'list_alt' },
      ]"
      @change="(k) => nav(`/work/lab/orders/${k}`)"
    />
    <PillTabs
      v-if="top === 'lab'"
      secondary
      :label="tr('Lab', 'ল্যাব')"
      :value="active"
      :tabs="[
        { key: 'info', label: tr('Lab details', 'ল্যাবের তথ্য'), icon: 'local_hospital' },
        { key: 'prices', label: tr('Tests & prices', 'পরীক্ষা ও দাম'), icon: 'payments' },
        { key: 'ranges', label: tr('Normal ranges', 'স্বাভাবিক মাত্রা'), icon: 'tune' },
      ]"
      @change="(k) => nav(k === 'info' ? '/work/lab/info' : `/work/lab/orders/${k}`)"
    />
  </div>
</template>
