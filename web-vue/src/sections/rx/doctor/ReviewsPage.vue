<script setup lang="ts">
// রিপোর্ট দেখার অনুরোধ — অ্যাপের RxReviewsPane / ReviewReadDialog / ReviewSettingsDialog, প্যানেলের reviewsView/readReport।
// ডাক্তার: সেটিং (ফি, টাকার নম্বর), টাকা মেলানো, রিপোর্ট পড়ে রোগীকে জানানো। সহকারী: শুধু টাকা মেলানো (doctorUid দিয়ে)।
import { computed, ref } from 'vue';
import { Action, EmptyState, ErrorBox, ErrorText, IconButton, PillTabs, SkeletonCards, Tag, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { forDoctor, payLabel, reviewStatus, type Actor, type RxReview } from './model';
import ReviewSettingsCard from './ReviewSettingsCard.vue';
import ReviewReadDialog from './ReviewReadDialog.vue';

type View = 'all' | 'claimed' | 'paid' | 'done';
const ORDER: Record<string, number> = { claimed: 0, paid: 1, unpaid: 2, done: 3 };

const props = defineProps<{ actor: Actor; link: (path: string, extra?: Record<string, string>) => string }>();
const doctor = computed(() => props.actor.role === 'doctor');
const q = useCall<{ items: RxReview[] }>('reviews_doctor', () => forDoctor(props.actor));
const view = ref<View>('all');
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const reading = ref<RxReview | null>(null);
const items = computed(() => [...(q.data?.items ?? [])].sort((a, b) => (ORDER[a.status] ?? 9) - (ORDER[b.status] ?? 9) || b.updatedAt - a.updatedAt));
const count = (s: string) => items.value.filter((r) => r.status === s).length;
const shown = computed(() => items.value.filter((r) => view.value === 'all' || r.status === view.value));

async function confirm(r: RxReview, ok: boolean) {
  busy.value = r.id + ok;
  err.value = null;
  try {
    await call('review_confirm', { ...forDoctor(props.actor), id: r.id, ok, ...(ok ? {} : { note: tr('TrxID not found', 'TrxID মেলেনি') }) });
    toast(ok ? tr('Confirmed', 'নিশ্চিত হয়েছে') : tr('The patient has been told', 'রোগীকে জানানো হয়েছে'));
    await q.refetch();
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = null;
}
function done() {
  reading.value = null;
  toast(tr('Sent', 'পাঠানো হয়েছে'));
  q.refetch();
}
</script>

<template>
  <div class="col gap-16">
    <ReviewSettingsCard v-if="doctor" />
    <div class="row wrap rxd-toolbar">
      <PillTabs
        secondary
        :label="tr('Reports', 'রিপোর্ট')"
        :value="view"
        :tabs="[
          { key: 'all', label: tr('All', 'সব') },
          { key: 'claimed', label: tr('Check payment', 'টাকা মেলাতে হবে'), count: count('claimed') },
          ...(doctor ? [{ key: 'paid', label: tr('To read', 'দেখতে হবে'), count: count('paid') }] : []),
          { key: 'done', label: tr('Done', 'দেখা হয়েছে') },
        ]"
        @change="(k) => (view = k as View)"
      />
      <span class="grow" />
      <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="q.refetch()" />
    </div>
    <ErrorText :text="err" />
    <SkeletonCards v-if="q.isPending" :n="3" :h="120" />
    <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
    <EmptyState
      v-else-if="!shown.length"
      icon="list_alt"
      :title="tr('No reports yet', 'এখনো কোনো রিপোর্ট নেই')"
      :text="view === 'all' ? tr('Reports from labs for your prescriptions appear here.', 'আপনার প্রেসক্রিপশনের পরীক্ষার রিপোর্ট এখানে আসবে।') : undefined"
    />
    <div v-else class="rxd-cards">
      <article v-for="r in shown" :key="r.id" class="card col gap-6">
        <div class="row top">
          <div class="col gap-2 grow">
            <span class="t-title-m">{{ r.patientName || '-' }}</span>
            <span class="t-body-s muted">{{ [r.labName, r.tests.join(', ')].filter(Boolean).join(' · ') }}</span>
          </div>
          <Tag :tone="r.status === 'claimed' || r.status === 'paid' ? 'good' : undefined">{{ reviewStatus(r.status) }}</Tag>
        </div>
        <span class="t-body-s muted">{{ timeAgo(r.updatedAt || r.createdAt) }}</span>
        <template v-if="r.status === 'claimed'">
          <span class="t-body-l semi rxd-break">{{ payLabel(r.method) + ' · TrxID: ' + (r.trxId ?? '') + (r.fee ? ' · ' + r.fee : '') }}</span>
          <span class="t-body-s muted">{{ tr('Check your payment app for this TrxID.', 'আপনার bKash/Nagad-এ এই TrxID মিলিয়ে দেখুন।') }}</span>
          <div class="row wrap">
            <Action primary icon="check_circle" :busy="busy === r.id + true" :disabled="!!busy" @click="confirm(r, true)">{{ tr('Received', 'টাকা পেয়েছি') }}</Action>
            <Action :busy="busy === r.id + false" :disabled="!!busy" @click="confirm(r, false)">{{ tr('Not found', 'মেলেনি') }}</Action>
          </div>
        </template>
        <div v-if="r.status === 'paid' && doctor">
          <Action primary icon="list_alt" @click="reading = r">{{ tr('Read report & reply', 'রিপোর্ট দেখে জানান') }}</Action>
        </div>
        <p v-if="r.status === 'done' && r.comment" class="t-body-m pre rxd-comment">{{ r.comment }}</p>
      </article>
    </div>
    <ReviewReadDialog v-if="reading" :r="reading" @close="reading = null" @done="done" />
  </div>
</template>
