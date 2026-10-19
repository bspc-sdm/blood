<script setup lang="ts">
// একটা প্রশ্ন — অ্যাপের QuestionDetailScreen
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, ErrorBox, ErrorText, IconButton, InfoText, Loading, Switch } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { call, errorText } from '@/lib/api';
import { useCall, queryClient } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { approvedBadge, useFlash, useMe } from './common';
import type { Answer, Question } from './paged';
import AuthorLine from './AuthorLine.vue';
import EditedNote from './EditedNote.vue';
import HealthReportDialog from './HealthReportDialog.vue';
import EditTextDialog from './EditTextDialog.vue';

const props = defineProps<{ id: string }>();
const nav = useNav();
const s = useSession();
const me = useMe();
const r = useCall<{ question: Question; answers: Answer[] }>('question', () => ({ id: props.id }));
const reply = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const [info, setInfo] = useFlash();
const editingQ = ref(false);
const editingA = ref<Answer | null>(null);
const deletingA = ref<Answer | null>(null);
const deletingQ = ref(false);
const report = ref<{ kind: 'question' | 'answer'; answerId?: string } | null>(null);
const delBusy = ref(false);
const delErr = ref<string | null>(null);

const changed = () => {
  queryClient.invalidateQueries({ queryKey: ['question'] });
  queryClient.invalidateQueries({ queryKey: ['questions'] });
};
const back = () => nav('/health/discussion');
const q = computed(() => r.data?.question);
const answers = computed(() => r.data?.answers ?? []);
const canAnswer = computed(() => !!approvedBadge(me.value));
const askReport = (t: { kind: 'question' | 'answer'; answerId?: string }) => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => (report.value = t));

async function sendAnswer() {
  if (!reply.value.trim()) return;
  busy.value = true;
  err.value = null;
  try {
    await call('answer', { id: props.id, text: reply.value.trim() });
    reply.value = '';
    changed();
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
async function setAnon(on: boolean) {
  try {
    await call('edit_question', { id: props.id, text: q.value!.text, anonymous: on });
    changed();
  } catch (e) {
    err.value = errorText(e);
  }
}
async function doReport() {
  const t = report.value!;
  await call('report', { kind: t.kind, id: props.id, ...(t.answerId ? { answerId: t.answerId } : {}) });
  report.value = null;
  setInfo(tr('Reported. An admin will review it.', 'রিপোর্ট পাঠানো হয়েছে। অ্যাডমিন দেখবেন।'));
}
async function saveQ(text: string) {
  await call('edit_question', { id: props.id, text });
  editingQ.value = false;
  changed();
}
async function saveA(text: string) {
  await call('edit_answer', { id: props.id, answerId: editingA.value!.id, text });
  editingA.value = null;
  changed();
}
async function delA() {
  delBusy.value = true;
  try {
    await call('delete_answer', { id: props.id, answerId: deletingA.value!.id });
    deletingA.value = null;
    changed();
  } catch (e) {
    delErr.value = errorText(e);
  }
  delBusy.value = false;
}
async function delQ() {
  delBusy.value = true;
  try {
    await call('delete_question', { id: props.id });
    deletingQ.value = false;
    queryClient.invalidateQueries({ queryKey: ['questions'] });
    back();
  } catch (e) {
    delErr.value = errorText(e);
  }
  delBusy.value = false;
}
</script>

<template>
  <DetailPane :title="tr('Question', 'প্রশ্ন')" :on-back="back">
    <template v-if="q" #actions>
      <IconButton v-if="!q.mine" icon="flag" :label="tr('Report', 'রিপোর্ট')" @click="askReport({ kind: 'question' })" />
      <IconButton v-if="q.mine" icon="edit" :label="tr('Edit', 'বদলান')" @click="editingQ = true" />
      <IconButton v-if="q.mine || me?.isAdmin" icon="delete" :label="tr('Delete', 'মুছুন')" @click="deletingQ = true" />
    </template>
    <Loading v-if="r.isPending" />
    <ErrorBox v-else-if="!q" :error="errorText(r.error)" :on-retry="() => r.refetch()" />
    <template v-else>
      <div class="col">
        <AuthorLine :name="q.authorName" :created-at="q.createdAt" />
        <p class="t-title-m pre" :style="{ fontWeight: 500 }">{{ q.text }}</p>
        <EditedNote v-if="q.editedAt" />
        <Switch v-if="q.mine" :label="tr('Hide my name', 'আমার নাম দেখাবে না')" :model-value="!!q.anonymous" @update:model-value="setAnon" />
      </div>
      <div class="section-label health-section">{{ answers.length ? `${tr('Answers', 'উত্তর')} (${num(answers.length)})` : tr('No answers yet', 'এখনো উত্তর নেই') }}</div>
      <div v-for="a in answers" :key="a.id" class="card col">
        <AuthorLine :name="a.authorName" :badge="a.badge" :created-at="a.createdAt" />
        <p class="t-body-l pre">{{ a.text }}</p>
        <EditedNote v-if="a.editedAt" />
        <div class="row health-text-actions">
          <Button v-if="!a.mine" variant="text" small @click="askReport({ kind: 'answer', answerId: a.id })">{{ tr('Report', 'রিপোর্ট') }}</Button>
          <Button v-if="a.mine" variant="text" small @click="editingA = a">{{ tr('Edit', 'বদলান') }}</Button>
          <Button v-if="a.mine || me?.isAdmin" variant="danger-text" small @click="deletingA = a">{{ tr('Delete', 'মুছুন') }}</Button>
        </div>
      </div>
      <div v-if="canAnswer" class="col">
        <label class="field">
          <span class="label">{{ tr('Write an answer', 'উত্তর লিখুন') }}</span>
          <textarea v-model="reply" class="textarea" rows="4" maxlength="2000" />
        </label>
        <Button icon="send" block :busy="busy" :disabled="!reply.trim()" @click="sendAnswer">{{ tr('Answer', 'উত্তর দিন') }}</Button>
      </div>
      <div v-else class="row wrap">
        <p class="t-body-s muted grow">{{ tr('Only verified health professionals can answer.', 'শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা উত্তর দিতে পারেন।') }}</p>
        <Button v-if="s.guest" variant="text" small @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
      </div>
      <ErrorText :text="err" />
      <InfoText :text="info" />
    </template>

    <HealthReportDialog v-if="report" :on-confirm="doReport" @close="report = null" />
    <EditTextDialog v-if="editingQ && q" :title="tr('Edit question', 'প্রশ্ন বদলান')" :initial="q.text" :max-len="1000" :on-save="saveQ" @close="editingQ = false" />
    <EditTextDialog v-if="editingA" :title="tr('Edit answer', 'উত্তর বদলান')" :initial="editingA.text" :max-len="2000" :on-save="saveA" @close="editingA = null" />
    <ConfirmDialog
      v-if="deletingA"
      :title="tr('Delete this answer?', 'উত্তরটা মুছবেন?')"
      :text="!deletingA.mine ? tr('You\'re deleting someone else\'s answer as an admin. This is recorded.', 'অ্যাডমিন হিসেবে অন্যের উত্তর মুছছেন। এটা রেকর্ডে থাকবে।') : undefined"
      :confirm="tr('Delete', 'মুছুন')"
      danger
      :busy="delBusy"
      :error="delErr"
      @close="() => { deletingA = null; delErr = null; }"
      @confirm="delA"
    />
    <ConfirmDialog
      v-if="deletingQ"
      :title="tr('Delete this question?', 'প্রশ্নটা মুছবেন?')"
      :confirm="tr('Delete', 'মুছুন')"
      danger
      :busy="delBusy"
      :error="delErr"
      @close="() => { deletingQ = false; delErr = null; }"
      @confirm="delQ"
    />
  </DetailPane>
</template>
