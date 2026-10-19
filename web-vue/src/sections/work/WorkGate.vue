<script setup lang="ts">
// লগইন ছাড়া বা কাজ না আসা পর্যন্ত (React-এর useWorkGate); সব ঠিক থাকলে ভেতরের পাতা
import { Button, ErrorText, Loading } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import WorkEmpty from './WorkEmpty.vue';

const s = useSession();
const nav = useNav();
</script>

<template>
  <Loading v-if="!s.ready" />
  <WorkEmpty
    v-else-if="s.guest"
    icon="storefront"
    :title="tr('Do you have a business?', 'আপনার কি কোনো ব্যবসা আছে?')"
    :text="tr('Ambulance, pharmacy, blood bank or lab — add it so patients nearby can find you.', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক বা প্যাথলজি — যোগ করলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন।')"
  >
    <Button icon="add" @click="s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav('/work/add'))">
      {{ tr('Yes, add it', 'হ্যাঁ, যোগ করুন') }}
    </Button>
  </WorkEmpty>
  <template v-else-if="!s.workLoaded">
    <div v-if="s.workError" class="col" :style="{ alignItems: 'center', padding: '24px' }">
      <ErrorText :text="s.workError" />
      <Button variant="text" @click="s.refreshWork()">{{ tr('Try again', 'আবার চেষ্টা করুন') }}</Button>
    </div>
    <Loading v-else />
  </template>
  <slot v-else />
</template>
