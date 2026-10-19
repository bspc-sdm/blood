<script setup lang="ts">
// "ওষুধ খোঁজা" ট্যাব (২.৩০.০): রোগীদের ওষুধ খোঁজার অনুরোধ — উপরে চালু/বন্ধ, প্রতিটায় এক চাপে "আছে"/"নেই"
import { computed } from 'vue';
import Redirect from '@/shell/Redirect.vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { myBusinessTitle, type Pharmacy } from './shared';
import BizPage from './BizPage.vue';
import WorkGate from './WorkGate.vue';
import PharmacyTabs from './PharmacyTabs.vue';
import FindRequests from './FindRequests.vue';

const s = useSession();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const ph = computed(() => s.work.pharmacy as Pharmacy | null);
const title = computed(() => myBusinessTitle('pharmacy'));
</script>

<template>
  <BizPage v-if="!ready" :title="title"><WorkGate /></BizPage>
  <Redirect v-else-if="ph?.status !== 'approved'" to="/work/pharmacy" />
  <BizPage v-else :title="title">
    <PharmacyTabs active="finds" />
    <p class="t-body-m muted">
      {{ tr('People nearby ask whether you have a medicine they couldn\'t find. One tap answers — they call you if you have it. They don\'t see each other\'s names or numbers.', 'কাছের মানুষ খুঁজে না পাওয়া ওষুধ আপনার কাছে আছে কি না জানতে চান। এক চাপে উত্তর দিন — থাকলে তাঁরা ফোন করবেন। কারো নাম-নম্বর দেখানো হয় না।') }}
    </p>
    <FindRequests />
  </BizPage>
</template>
