<script setup lang="ts">
// ===== ডেমো (শুধু পরীক্ষার জন্য) — ডেমো মুছলে এই ফোল্ডার আর LoginFlow/Profile-এর "ডেমো" লাইনগুলো মুছবেন =====
// লগইন পাতায় রোলের তালিকা: চাপলেই সেই ডেমো অ্যাকাউন্টে ঢোকা (ইমেইল/কোড নেই)। সার্ভারে ডেমো চালু না থাকলে কিছুই দেখায় না।
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { signInWithCustomToken } from 'firebase/auth';
import { auth } from '@/lib/firebase';
import { postPublic, errorText } from '@/lib/api';
import { setCareMode } from '@/lib/care';
import { tr } from '@/lib/i18n';
import { queryClient } from '@/lib/query';
import { ErrorText, ListRow, toast } from '@/ui/kit';

interface Role { key: string; name: string; role: string }
const emit = defineEmits<{ done: [] }>();
const roles = ref<Role[]>([]);
const busy = ref<string | null>(null);
const error = ref<string | null>(null);
let live = true;

onMounted(() => {
  postPublic<{ active: boolean; roles: Role[] }>('/api/auth/verify-code', { demoRoles: true })
    .then((r) => {
      if (live && r.active) roles.value = r.roles;
    })
    .catch(() => {});
});
onBeforeUnmount(() => {
  live = false;
});

async function pick(key: string) {
  busy.value = key;
  error.value = null;
  try {
    const r = await postPublic<{ token?: string; restored?: boolean }>('/api/auth/verify-code', { demo: key });
    if (!r.token) throw new Error(tr('Login failed', 'লগইন হয়নি'));
    // আগের অ্যাকাউন্ট থেকে আলাদা করে বের হওয়া নয় — নতুন লগইনেই বদলায় (বের হলে পাতা সরে গিয়ে ভুল দেখানো যেত না)
    setCareMode(null);
    await signInWithCustomToken(auth, r.token);
    queryClient.clear();
    if (r.restored) toast(tr('Account deletion cancelled.', 'অ্যাকাউন্ট মোছা বাতিল হয়েছে।'));
    emit('done');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = null;
  }
}
</script>

<template>
  <section v-if="roles.length" class="col gap-8" :aria-label="tr('Demo', 'ডেমো')" :style="{ marginTop: '8px', paddingTop: '12px', borderTop: '1px solid var(--surface-variant)' }">
    <p class="t-title-s">{{ tr('Demo (for testing)', 'ডেমো (পরীক্ষার জন্য)') }}</p>
    <div class="col">
      <ListRow
        v-for="r in roles"
        :key="r.key"
        icon="person_outline"
        :title="r.role"
        :sub="busy === r.key ? tr('Logging in…', 'ঢুকছে…') : r.name"
        :on-click="busy ? undefined : () => pick(r.key)"
      />
    </div>
    <ErrorText :text="error" />
  </section>
</template>
