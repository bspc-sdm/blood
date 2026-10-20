<script setup lang="ts">
// ডোনারের দিক থেকে রিকোয়েস্ট — অ্যাপের RequestDetailScreen (RequestScreens.kt)।
// অতিথি: আশেপাশের তালিকা থেকে পাওয়া তথ্য (নাম/ফোন/নোট ছাড়া); "রক্ত দেব" চাপলে লগইন আর রক্তদাতার তথ্য।
import { computed } from 'vue';
import { Loading } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import GuestDetail from './GuestDetail.vue';
import MemberDetail from './MemberDetail.vue';

defineProps<{ id: string }>();
const s = useSession();
const title = computed(() => tr('Blood Request', 'রক্তের রিকোয়েস্ট'));
</script>

<template>
  <DetailPane v-if="!s.ready" :title="title" back-to="/"><Loading /></DetailPane>
  <GuestDetail v-else-if="s.guest" :id="id" :title="title" />
  <MemberDetail v-else :id="id" :title="title" />
</template>
