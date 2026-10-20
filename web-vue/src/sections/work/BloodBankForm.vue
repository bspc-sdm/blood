<script setup lang="ts">
// /work/bloodbank/new, /work/bloodbank/:id
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import FormPage from '@/shell/FormPage.vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { MAX_BLOODBANKS, myBusinessTitle, type BloodBank } from './shared';
import WorkGate from './WorkGate.vue';
import WorkEmpty from './WorkEmpty.vue';
import BloodBankEditor from './BloodBankEditor.vue';

const props = defineProps<{ id: string | null }>();
const s = useSession();
const nav = useNav();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const existing = computed(() => (props.id ? (s.work.bloodBanks as BloodBank[]).find((b) => b.id === props.id) ?? null : null));
const title = computed(() => (existing.value ? tr('Edit blood bank', 'ব্লাড ব্যাংক বদলান') : tr('Add blood bank', 'ব্লাড ব্যাংক যোগ')));
</script>

<template>
  <FormPage :title="title" back-to="/work/bloodbank">
    <WorkGate v-if="!ready" />
    <WorkEmpty v-else-if="id && !existing" icon="bloodtype" :title="tr('Not found', 'পাওয়া যায়নি')">
      <RouterLink to="/work/bloodbank" class="btn">{{ myBusinessTitle('bloodbank') }}</RouterLink>
    </WorkEmpty>
    <WorkEmpty v-else-if="!id && s.work.bloodBanks.length >= MAX_BLOODBANKS" icon="bloodtype" :title="tr('Up to 3 blood banks per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ৩টা ব্লাড ব্যাংক')" />
    <BloodBankEditor v-else :existing="existing" @done="(flash) => nav('/work/bloodbank', { state: { flash } })" />
  </FormPage>
</template>
