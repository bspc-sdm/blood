<script setup lang="ts">
// ব্যাগ, হাসপাতাল, তারিখ-সময়, জরুরি, যাতায়াত খরচ, অতিরিক্ত তথ্য আর যোগাযোগ নম্বর বদলানো যায়; গ্রুপ আর এলাকা নয়
import { computed } from 'vue';
import { ErrorText, Loading } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useMyRequest } from '../data';
import { isActive } from '../model';
import LoginNeeded from '../LoginNeeded.vue';
import EditRequestForm from './EditRequestForm.vue';

const props = defineProps<{ id: string }>();
const s = useSession();
const title = computed(() => tr('Edit Request', 'রিকোয়েস্ট এডিট'));
const q = useMyRequest(() => props.id);
const back = computed(() => `/my-requests/${props.id}`);
const r = computed(() => q.data?.r);
</script>

<template>
  <FormPage v-if="!s.ready" :title="title" :back-to="back"><Loading /></FormPage>
  <FormPage v-else-if="s.guest" :title="title" :back-to="back">
    <LoginNeeded :reason="tr('Log in to see your requests', 'আপনার রিকোয়েস্ট দেখতে লগইন করুন')" />
  </FormPage>
  <FormPage v-else-if="q.isLoading" :title="title" :back-to="back"><Loading /></FormPage>
  <FormPage v-else-if="!r || r.requesterUid !== s.uid || !isActive(r)" :title="title" :back-to="back">
    <ErrorText :text="!r ? tr('Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি') : tr('This request is closed', 'রিকোয়েস্টটা বন্ধ হয়ে গেছে')" />
  </FormPage>
  <EditRequestForm v-else :key="r.id" :title="title" :back="back" :r="r" />
</template>
