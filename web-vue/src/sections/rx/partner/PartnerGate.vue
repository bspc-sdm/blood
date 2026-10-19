<script setup lang="ts">
// লগইন/কাজ আনা বাকি, বা ব্যবসা নেই/অনুমোদন হয়নি — বন্ধুসুলভ অবস্থা আর নিজের ব্যবসার পাতার লিংক (React-এর usePartnerGate)
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { EmptyState, Icon } from '@/ui/kit';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { statusText } from '../../work/shared';
import WorkGate from '../../work/WorkGate.vue';

const props = defineProps<{ kind: 'pharmacy' | 'lab' }>();
const s = useSession();
const workReady = computed(() => s.ready && !s.guest && s.workLoaded);
const biz = computed(() => (props.kind === 'pharmacy' ? s.work.pharmacy : s.work.lab) as { status: string; name: string } | null);
const to = computed(() => (props.kind === 'pharmacy' ? '/work/pharmacy' : '/work/lab'));
const icon = computed(() => (props.kind === 'pharmacy' ? 'storefront' : 'local_hospital'));
const title = computed(() =>
  !biz.value
    ? props.kind === 'pharmacy' ? tr('Add your pharmacy first', 'আগে আপনার ফার্মেসি যোগ করুন') : tr('Add your lab first', 'আগে আপনার প্যাথলজি যোগ করুন')
    : props.kind === 'pharmacy' ? tr('Only approved pharmacies can do this', 'শুধু অনুমোদিত ফার্মেসি এটা করতে পারে') : tr('Only approved labs can do this', 'শুধু অনুমোদিত প্যাথলজি এটা করতে পারে'),
);
const text = computed(() =>
  !biz.value
    ? props.kind === 'pharmacy'
      ? tr('Once your pharmacy is approved, give medicines from prescriptions, take online orders and keep your stock here.', 'ফার্মেসি অনুমোদন হলে এখানে প্রেসক্রিপশনের ওষুধ দেওয়া, অনলাইন অর্ডার আর স্টক রাখা যাবে।')
      : tr('Once your lab is approved, take tests from prescriptions and give reports here.', 'প্যাথলজি অনুমোদন হলে এখানে প্রেসক্রিপশনের পরীক্ষা নেওয়া আর রিপোর্ট দেওয়া যাবে।')
    : `${biz.value.name} · ${statusText(biz.value.status)}`,
);
</script>

<template>
  <WorkGate v-if="!workReady" />
  <slot v-else-if="biz && biz.status === 'approved'" />
  <div v-else class="rxp-gate">
    <EmptyState :icon="icon" :title="title" :text="text">
      <RouterLink class="btn tonal has-icon" :to="to"><Icon :name="icon" />{{ kind === 'pharmacy' ? tr('My pharmacy', 'আমার ফার্মেসি') : tr('My lab', 'আমার প্যাথলজি') }}</RouterLink>
    </EmptyState>
  </div>
</template>
