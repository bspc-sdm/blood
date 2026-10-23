<script setup lang="ts">
// "ওষুধ খোঁজা" (২.৩০.০; আমার কাজ CONTRACT §২ থেকে "অর্ডার"-এর চতুর্থ ভাগ): রোগীদের ওষুধ খোঁজার অনুরোধ — উপরে চালু/বন্ধ, প্রতিটায় কতটা আছে
import { computed } from 'vue';
import Redirect from '@/shell/Redirect.vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { myBusinessTitle, type Pharmacy } from './shared';
import BizPage from './BizPage.vue';
import WorkGate from './WorkGate.vue';
import PharmacyTabs from './PharmacyTabs.vue';
import FindRequests from './FindRequests.vue';
import PharmOrderSegs from '../rx/partner/PharmOrderSegs.vue';

const s = useSession();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const ph = computed(() => s.work.pharmacy as Pharmacy | null);
const title = computed(() => myBusinessTitle('pharmacy'));
// ২০২৬-১০: "এক চাপে" নয় — থাকলে কতটা আছে লিখে পাঠান, না থাকলে কিছু করতে হয় না (MEDFIND.md)
const hint = computed(() => tr('People nearby ask whether you have a medicine they couldn\'t find, and how much they need. If you have it, write how many and send — they call you. If not, you don\'t need to do anything. No one\'s name or number is shown.', 'কাছের মানুষ খুঁজে না পাওয়া ওষুধ আপনার কাছে আছে কি না, কতটা লাগবে জানান। থাকলে কতটা আছে লিখে পাঠান — তাঁরা ফোন করবেন। না থাকলে কিছু করতে হবে না। কারো নাম-নম্বর দেখানো হয় না।')); // bn-ok
</script>

<template>
  <BizPage v-if="!ready" :title="title"><WorkGate /></BizPage>
  <Redirect v-else-if="ph?.status !== 'approved'" to="/work/pharmacy" />
  <BizPage v-else :title="title">
    <PharmacyTabs active="finds" />
    <PharmOrderSegs value="finds" />
    <p class="t-body-m muted">
      {{ hint }}
    </p>
    <FindRequests />
  </BizPage>
</template>
