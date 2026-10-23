<script setup lang="ts">
// "ব্লক" (call_block — দুজনের কেউই আর অ্যাপে কল করতে পারেন না; ফোনে কল আলাদা) আর "রিপোর্ট" (call_report, কারণ + নোট)।
// কল শেষের পর্দায় আর কলের তালিকার সারিতে।
import { ref } from 'vue';
import { Button, ChoiceChips, ConfirmDialog, Dialog, ErrorText, TextArea, toast } from '@/ui/kit';
import { cx } from '@/ui/composables';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { queryClient } from '@/lib/query';
import { callBlock, callReport, type ReportReason } from './api';
import { T, reasonText } from './text';

const props = withDefaults(defineProps<{ peerUid: string; peerName: string; callId: string; dark?: boolean; small?: boolean }>(), { dark: false, small: true });
const emit = defineEmits<{ open: []; blocked: [] }>();
const blocking = ref(false);
const reporting = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);
const reason = ref<ReportReason>('harassment');
const note = ref('');
const done = ref<'blocked' | 'reported' | null>(null);
const REASONS: ReportReason[] = ['harassment', 'spam', 'fake', 'other'];

function openBlock() {
  err.value = null;
  blocking.value = true;
  emit('open');
}
function openReport() {
  err.value = null;
  reporting.value = true;
  emit('open');
}
async function block() {
  busy.value = true;
  err.value = null;
  try {
    await callBlock(props.peerUid, props.callId);
    blocking.value = false;
    done.value = 'blocked';
    toast(T.blocked());
    void queryClient.invalidateQueries({ queryKey: ['call_blocks'] });
    emit('blocked');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function report() {
  busy.value = true;
  err.value = null;
  try {
    await callReport(props.callId, reason.value, note.value.trim());
    reporting.value = false;
    done.value = done.value ?? 'reported';
    toast(T.reported());
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="row wrap gap-8" data-testid="call-flag">
    <button v-if="done !== 'blocked'" type="button" :class="cx('btn text has-icon', small && 'small', dark && 'call-dark-text')" data-testid="call-block" @click="openBlock">{{ T.block() }}</button>
    <span v-else :class="cx('t-label-m', !dark && 'muted')">{{ T.blocked() }}</span>
    <button type="button" :class="cx('btn text has-icon', small && 'small', dark && 'call-dark-text')" data-testid="call-report" @click="openReport">{{ T.report() }}</button>
    <ConfirmDialog
      v-if="blocking"
      :title="T.blockTitle(peerName || '—')"
      :text="T.blockText()"
      :confirm="T.block()"
      danger
      :busy="busy"
      :error="err"
      @confirm="block"
      @close="blocking = false"
    />
    <Dialog v-if="reporting" :title="T.reportTitle()" size="small" @close="() => !busy && (reporting = false)">
      <div class="col gap-12" data-testid="call-report-dialog">
        <span class="t-body-m">{{ peerName }}</span>
        <ChoiceChips v-model="reason" :options="REASONS.map((r) => ({ value: r, label: reasonText(r) }))" />
        <TextArea v-model="note" :label="T.reportNote()" maxlength="300" />
        <ErrorText :text="err" />
        <div class="row">
          <span class="grow" />
          <Button variant="text" :disabled="busy" @click="reporting = false">{{ tr('Cancel', 'বাতিল') }}</Button>
          <Button :busy="busy" data-testid="call-report-send" @click="report">{{ T.reportSend() }}</Button>
        </div>
      </div>
    </Dialog>
  </div>
</template>
