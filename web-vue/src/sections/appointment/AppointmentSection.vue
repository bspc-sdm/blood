<script setup lang="ts">
// অ্যাপয়েন্টমেন্ট অংশ: /appointment/* আর /my-appointments/* (router.ts দুই জায়গায় বসায়)।
// কোন পাতা — পুরো ঠিকানা দেখে (matchPath); তালিকা আর বিস্তারিত একই কম্পোনেন্ট, তাই ফিল্টার হারায় না।
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { matchPath } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';
import DoctorsPage from './DoctorsPage.vue';
import HomeCarePage from './HomeCarePage.vue';
import MyAppointmentsPage from './MyAppointmentsPage.vue';

const route = useRoute();
const page = computed(() => {
  const m = (p: string) => matchPath({ path: p, end: true }, route.path);
  const doctor = m('/appointment/doctor/:id');
  if (m('/appointment') || doctor) return { k: 'doctors', id: doctor?.params.id ?? null };
  const home = m('/appointment/home-care/:id');
  if (m('/appointment/home-care') || home) return { k: 'home', id: home?.params.id ?? null };
  const mine = m('/my-appointments/:id');
  if (m('/my-appointments') || mine) return { k: 'mine', id: mine?.params.id ?? null };
  return { k: 'none', id: null };
});
</script>

<template>
  <DoctorsPage v-if="page.k === 'doctors'" :detail-id="page.id" />
  <HomeCarePage v-else-if="page.k === 'home'" :detail-id="page.id" />
  <MyAppointmentsPage v-else-if="page.k === 'mine'" :detail-id="page.id" />
  <NotFound v-else />
</template>
