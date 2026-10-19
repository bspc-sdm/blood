<script setup lang="ts">
// লগইন ছাড়া (অতিথি) যে পাতায় অ্যাকাউন্ট লাগে: ছোট কার্ড, "লগইন" চাপলে লগইন-গেট
import { Button, EmptyState } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';

const props = withDefaults(defineProps<{ reason: string; icon?: string; then?: () => void; donor?: boolean }>(), { icon: 'person_outline' });
const s = useSession();
const login = () => s.requireLogin(props.reason, props.then ?? (() => {}), { donor: props.donor });
</script>

<template>
  <EmptyState :icon="icon" :title="reason">
    <Button @click="login">{{ tr('Log in', 'লগইন') }}</Button>
  </EmptyState>
</template>
