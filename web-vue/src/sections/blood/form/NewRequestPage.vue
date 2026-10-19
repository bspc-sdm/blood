<script setup lang="ts">
import { computed } from 'vue';
import { Loading } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import LoginNeeded from '../LoginNeeded.vue';
import NewRequestForm from './NewRequestForm.vue';

const s = useSession();
const title = computed(() => tr('Create Request', 'রিকোয়েস্ট দিন'));
</script>

<template>
  <FormPage v-if="!s.ready || (!s.guest && !s.profileLoaded)" :title="title" back-to="/"><Loading /></FormPage>
  <FormPage v-else-if="s.guest || !s.profile?.name.trim() || !s.profile?.phone" :title="title" back-to="/">
    <LoginNeeded icon="bloodtype" :reason="tr('Log in to request blood', 'রক্তের রিকোয়েস্ট দিতে লগইন করুন')" />
  </FormPage>
  <NewRequestForm v-else :title="title" />
</template>
