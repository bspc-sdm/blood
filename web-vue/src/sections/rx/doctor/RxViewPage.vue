<script setup lang="ts">
// একটা প্রেসক্রিপশন খুলে কাগজ, ছাপা, বাতিল (rx_get; বাতিল কারণসহ — RxCancelDialog) — অ্যাপের RxDetailDialog(asDoctor) / RxPaper; ছাপায় শুধু কাগজটা।
import { computed, ref } from 'vue';
import { Button, ErrorBox, SkeletonCards, Tag, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import RxPaper from '../../health/RxPaper.vue';
import { SELF, statusTag, useRxInvalidate, type DoctorLab, type DoctorRx } from './model';
import type { Link } from './patients';
import DoctorLabList from './DoctorLabList.vue';
import RxCancelDialog from './RxCancelDialog.vue';

const PRINT_PATH = 'M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z';
const props = defineProps<{ id: string; link: Link }>();
const nav = useNav();
const invalidate = useRxInvalidate();
const q = useCall<{ item: DoctorRx; labOrders?: DoctorLab[] }>('rx_get', () => ({ id: props.id, ...SELF }));
const cancelling = ref(false);
const rx = computed(() => q.data?.item ?? null);
const labs = computed(() => q.data?.labOrders ?? []);
const back = () => (window.history.length > 1 ? nav(-1) : nav(props.link('/list')));
const active = computed(() => rx.value?.status !== 'cancelled');

async function cancelled() {
  invalidate();
  await q.refetch();
  cancelling.value = false;
  toast(tr('Cancelled', 'বাতিল করা হয়েছে'));
}
const askCancel = () => (cancelling.value = true);
const print = () => window.print();
</script>

<template>
  <SkeletonCards v-if="q.isPending" :n="1" :h="420" />
  <ErrorBox v-else-if="q.isError || !rx" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
  <div v-else class="rxd-view">
    <div class="row wrap rxd-toolbar rxd-view-bar">
      <Button variant="text" icon="arrow_back" @click="back">{{ tr('Back', 'ফিরে যান') }}</Button>
      <span class="grow" />
      <Button variant="tonal" class="rxd-print-btn" @click="print"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path :d="PRINT_PATH" /></svg>{{ tr('Print / PDF', 'ছাপুন / PDF') }}</Button>
    </div>
    <div class="rxd-paper">
      <RxPaper :rx="rx" />
    </div>
    <aside class="col gap-12 rxd-view-side">
      <section class="card col gap-6">
        <span class="t-label-m muted">{{ tr('Code', 'কোড') }}</span>
        <span class="t-title-l health-code">{{ rx.code }}</span>
        <span class="row wrap gap-6">
          <Tag :tone="active ? 'good' : 'error'">{{ statusTag(rx.status) }}</Tag>
          <Tag v-if="rx.linked" tone="primary">{{ tr('In app', 'অ্যাপে আছেন') }}</Tag>
        </span>
        <p v-if="!rx.linked && active" class="t-body-s muted">{{ tr('The patient adds the prescription to their own app with this number and the code.', 'রোগী এই নম্বর আর কোড দিয়ে নিজের অ্যাপে প্রেসক্রিপশন যোগ করবেন।') }}</p>
      </section>
      <!-- পার্টনার ল্যাবের পরীক্ষা — অবস্থা সবসময়, ফল শুধু রিপোর্ট দেখার টাকা মিললে / ফি না থাকলে -->
      <section v-if="rx.tests?.length || labs.length" class="card col gap-6" :aria-label="tr('Test reports', 'পরীক্ষার রিপোর্ট')">
        <span class="t-label-m muted">{{ tr('Test reports', 'পরীক্ষার রিপোর্ট') }}</span>
        <DoctorLabList v-if="labs.length" :labs="labs" />
        <span v-else class="t-body-s muted">{{ tr('The patient has not sent these tests to a partner lab yet', 'রোগী এখনো কোনো পার্টনার ল্যাবে পরীক্ষা পাঠাননি') }}</span>
      </section>
      <Button icon="edit_note" variant="outline" @click="nav(link('/write'))">{{ tr('New prescription', 'নতুন প্রেসক্রিপশন') }}</Button>
      <Button v-if="active" variant="danger-text" icon="close" @click="askCancel">{{ tr('Cancel this prescription', 'এই প্রেসক্রিপশন বাতিল করুন') }}</Button>
    </aside>

    <Teleport to="body">
      <div class="health-print rxd-print" aria-hidden="true"><RxPaper :rx="rx" print /></div>
    </Teleport>

    <RxCancelDialog v-if="cancelling" :rx-id="rx.id" @close="cancelling = false" @done="cancelled" />
  </div>
</template>
