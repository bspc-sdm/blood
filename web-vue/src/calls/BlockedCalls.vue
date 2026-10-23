<script setup lang="ts">
// সেটিংস: অ্যাপে কলে যাদের ব্লক করেছেন (call_blocks) — আনব্লক (call_unblock)
import { computed, ref } from 'vue';
import { Button, Dialog, EmptyState, ErrorText, ListRow, Loading, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { callBlocks, callUnblock } from './api';
import { T } from './text';
import SLabel from '@/sections/account/SLabel.vue';
import { featureOn } from '@/lib/features';

const open = ref(false);
const q = useQ(() => ({ queryKey: ['call_blocks'], queryFn: callBlocks, enabled: open.value, staleTime: 0 }));
const list = computed(() => q.data ?? []);
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
async function unblock(uid: string) {
  busy.value = uid;
  err.value = null;
  try {
    await callUnblock(uid);
    toast(T.unblocked());
    await q.refetch();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = null;
  }
}
</script>

<template>
  <section v-if="featureOn('calls_audio') || featureOn('chat')" class="acct-scard" data-testid="call-blocks">
    <SLabel icon="call">{{ T.inApp() }}</SLabel>
    <ListRow icon="close" :title="T.blockedList()" :on-click="() => (open = true)" />
    <Dialog v-if="open" :title="T.blockedList()" size="small" @close="open = false">
      <div class="col gap-8" data-testid="call-blocks-dialog">
        <Loading v-if="q.isLoading" inline />
        <ErrorText v-else-if="q.error" :text="errorText(q.error)" />
        <EmptyState v-else-if="!list.length" icon="call" :title="T.blockedNone()" />
        <div v-for="b in list" :key="b.uid" class="row gap-8">
          <span class="t-body-l grow ellipsis">{{ b.name || '—' }}</span>
          <Button small variant="outline" :busy="busy === b.uid" :disabled="!!busy" @click="unblock(b.uid)">{{ T.unblock() }}</Button>
        </div>
        <ErrorText :text="err" />
      </div>
    </Dialog>
  </section>
</template>
