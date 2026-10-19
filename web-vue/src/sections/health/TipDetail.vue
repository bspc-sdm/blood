<script setup lang="ts">
import { ref } from 'vue';
import { ConfirmDialog, IconButton, InfoText } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { call, errorText } from '@/lib/api';
import { queryClient } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { useFlash, useMe } from './common';
import type { Tip } from './paged';
import AuthorLine from './AuthorLine.vue';
import EditedNote from './EditedNote.vue';
import HealthReportDialog from './HealthReportDialog.vue';
import EditTextDialog from './EditTextDialog.vue';

const props = defineProps<{ tip: Tip }>();
const nav = useNav();
const s = useSession();
const me = useMe();
const editing = ref(false);
const reporting = ref(false);
const deleting = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);
const [info, setInfo] = useFlash();
const back = () => nav('/health/tips');
async function doReport() {
  await call('report', { kind: 'tip', id: props.tip.id });
  reporting.value = false;
  setInfo(tr('Reported. An admin will review it.', 'রিপোর্ট পাঠানো হয়েছে। অ্যাডমিন দেখবেন।'));
}
async function save(body: string, title: string) {
  await call('edit_tip', { id: props.tip.id, title, body });
  editing.value = false;
  await queryClient.invalidateQueries({ queryKey: ['tips'] });
}
async function del() {
  busy.value = true;
  try {
    await call('delete_tip', { id: props.tip.id });
    deleting.value = false;
    queryClient.invalidateQueries({ queryKey: ['tips'] });
    back();
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
</script>

<template>
  <DetailPane :title="tr('Health Tips', 'হেলথ টিপস')" :on-back="back">
    <template #actions>
      <IconButton v-if="!tip.mine" icon="flag" :label="tr('Report', 'রিপোর্ট')" @click="s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => (reporting = true))" />
      <IconButton v-if="tip.mine" icon="edit" :label="tr('Edit', 'বদলান')" @click="editing = true" />
      <IconButton v-if="tip.mine || me?.isAdmin" icon="delete" :label="tr('Delete', 'মুছুন')" @click="deleting = true" />
    </template>
    <h2 class="t-headline">{{ tip.title }}</h2>
    <AuthorLine :name="tip.authorName" :badge="tip.badge" :created-at="tip.createdAt" />
    <p class="t-body-l pre">{{ tip.body }}</p>
    <EditedNote v-if="tip.editedAt" />
    <InfoText :text="info" />
    <HealthReportDialog v-if="reporting" :on-confirm="doReport" @close="reporting = false" />
    <EditTextDialog v-if="editing" :title="tr('Edit tip', 'টিপস বদলান')" :initial="tip.body" :initial-title="tip.title" :max-len="3000" :on-save="save" @close="editing = false" />
    <ConfirmDialog
      v-if="deleting"
      :title="tr('Delete this tip?', 'টিপসটা মুছবেন?')"
      :confirm="tr('Delete', 'মুছুন')"
      danger
      :busy="busy"
      :error="err"
      @close="() => { deleting = false; err = null; }"
      @confirm="del"
    />
  </DetailPane>
</template>
