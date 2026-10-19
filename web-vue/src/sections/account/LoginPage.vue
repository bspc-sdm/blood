<script setup lang="ts">
// /login?next=/path — লগইন থাকলে সরাসরি next (না থাকলে প্রোফাইল); নইলে লগইনের ডায়ালগ খোলে, লগইন হলে next-এ
import { computed, watch } from 'vue';
import { Button, EmptyState, Loading } from '@/ui/kit';
import Redirect from '@/shell/Redirect.vue';
import { usePageTitle } from '@/shell/chrome';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useSearchParams } from '@/lib/nav';
import { safeNext } from './util';

const s = useSession();
const [params] = useSearchParams();
const next = computed(() => safeNext(params.value.get('next')) ?? '/profile');
usePageTitle(() => tr('Log in', 'লগইন'));

// একবারই নিজে থেকে খোলে (বন্ধ করলে আবার জোর করে খোলে না)
let opened = false;
watch(
  () => [s.ready, s.guest],
  () => {
    if (s.ready && s.guest && !opened) {
      opened = true;
      s.login();
    }
  },
  { immediate: true },
);
</script>

<template>
  <Loading v-if="!s.ready" />
  <Redirect v-else-if="!s.guest" :to="next" />
  <div v-else class="acct-page">
    <EmptyState icon="person_outline" :title="tr('Log in', 'লগইন')" :text="tr('We will email you a code. No password needed.', 'ইমেইলে একটা কোড যাবে, পাসওয়ার্ড লাগবে না।')">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
  </div>
</template>
