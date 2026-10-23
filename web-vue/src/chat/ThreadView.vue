<script setup lang="ts">
// একটা কথোপকথন: উপরে কে + প্রসঙ্গ + অ্যাপে কল বোতাম + ব্লক; মাঝে মেসেজ (উপরে "আগের মেসেজ"); নিচে লেখা/ছবি/লোকেশন।
// শুধু পড়া যায় (সম্পর্ক শেষ, ব্লক) হলে লেখার জায়গার বদলে কারণ। খোলা আর পাতা সামনে থাকলে "পড়া হয়েছে" (chat_read)।
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Avatar, Button, ChoiceChips, ConfirmDialog, Dialog, ErrorBox, ErrorText, Icon, IconButton, Loading, TextArea, toast } from '@/ui/kit';
import { cx, useNow } from '@/ui/composables';
import { errorText } from '@/lib/api';
import { currentPosition } from '@/lib/geo';
import { formatDayOf } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { featureOn } from '@/lib/features';
import ContactActions from '@/calls/ContactActions.vue';
import type { CallCtx } from '@/calls/api';
import { REPORT_REASONS, type ChatMessage, type ReportReason } from './api';
import { TEXT_MAX, block, chat, discard, loadLatest, loadOlder, markRead, report, retry, send, unsend } from './store';
import { compressPhoto, imgSrc } from './photo';
import { T, ctxText, reasonText } from './text';
import MessageBubble from './MessageBubble.vue';

const props = defineProps<{ threadId: string; two: boolean }>();
const nav = useNav();
const now = useNow(30_000);

const info = computed(() => chat.info[props.threadId] ?? null);
const list = computed(() => chat.msgs[props.threadId] ?? []);
const peerName = computed(() => info.value?.peer.name || '—');
const canSend = computed(() => info.value?.canSend !== false);
const closedText = computed(() => {
  const c = info.value?.closed;
  if (c === 'blocked') return T.blockedSub();
  if (c === 'not_allowed') return T.notAllowedSub();
  return T.readOnlySub();
});
const mine = (m: ChatMessage) => !!chat.me && m.from === chat.me;
/** দিনের ভাগ: আগের মেসেজের দিন আলাদা হলে তারিখ */
const dayOf = (i: number) => {
  const m = list.value[i];
  const d = m.at ? formatDayOf(m.at) : '';
  return i === 0 || (list.value[i - 1].at && formatDayOf(list.value[i - 1].at) !== d) ? d : '';
};

// ---------- স্ক্রল ----------
const scroller = ref<HTMLElement | null>(null);
const atBottom = ref(true);
const newBelow = ref(false);
function onScroll() {
  const el = scroller.value;
  if (!el) return;
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  if (atBottom.value) newBelow.value = false;
}
function toBottom() {
  nextTick(() => {
    const el = scroller.value;
    if (el) el.scrollTop = el.scrollHeight;
    atBottom.value = true;
    newBelow.value = false;
  });
}
watch(
  () => list.value.at(-1)?.id,
  (id, old) => {
    if (!id || id === old) return;
    const last = list.value.at(-1)!;
    if (atBottom.value || mine(last) || !old) toBottom();
    else newBelow.value = true;
    readIfSeen();
  },
);
const olderBusy = ref(false);
async function older() {
  const el = scroller.value;
  const h = el?.scrollHeight ?? 0;
  olderBusy.value = true;
  try {
    await loadOlder(props.threadId);
    await nextTick();
    if (el) el.scrollTop += el.scrollHeight - h;
  } catch (e) {
    toast(errorText(e));
  } finally {
    olderBusy.value = false;
  }
}

// ---------- পড়া ----------
function readIfSeen() {
  if (document.visibilityState === 'visible' && atBottom.value) void markRead(props.threadId);
}
const onVis = () => readIfSeen();
watch(atBottom, (b) => b && readIfSeen());
onMounted(async () => {
  chat.openThread = props.threadId;
  document.addEventListener('visibilitychange', onVis);
  if (list.value.length) toBottom();
  await loadLatest(props.threadId);
  toBottom();
  readIfSeen();
});
onBeforeUnmount(() => {
  if (chat.openThread === props.threadId) chat.openThread = null;
  document.removeEventListener('visibilitychange', onVis);
});

// ---------- লেখা ----------
const text = ref('');
const sendErr = ref<string | null>(null);
const tooLong = computed(() => text.value.length > TEXT_MAX);
async function sendText() {
  const t = text.value.trim();
  if (!t || tooLong.value || !canSend.value) return;
  text.value = '';
  sendErr.value = null;
  toBottom();
  await send(props.threadId, { text: t });
}
const finePointer = typeof window !== 'undefined' && window.matchMedia?.('(pointer: fine)').matches;
function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && finePointer) {
    e.preventDefault();
    void sendText();
  }
}

// ---------- ছবি ----------
const fileIn = ref<HTMLInputElement | null>(null);
const photoBusy = ref(false);
async function picked(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  input.value = '';
  if (!f) return;
  photoBusy.value = true;
  sendErr.value = null;
  try {
    const b64 = await compressPhoto(f);
    toBottom();
    await send(props.threadId, { photo: b64 });
  } catch (err) {
    sendErr.value = err instanceof Error && err.message ? err.message : T.photoFailed();
  } finally {
    photoBusy.value = false;
  }
}
const viewing = ref<ChatMessage | null>(null);
let refetchedAt = 0;
/** সই করা লিংকের মেয়াদ গেছে (৪০৩) — নতুন লিংকের জন্য একবার আবার আনা */
function photoExpired() {
  if (Date.now() - refetchedAt < 30_000) return;
  refetchedAt = Date.now();
  void loadLatest(props.threadId);
}

// ---------- লোকেশন ----------
const askLoc = ref(false);
const locBusy = ref(false);
const locErr = ref<string | null>(null);
async function sendLocation() {
  locBusy.value = true;
  locErr.value = null;
  try {
    const p = await currentPosition();
    askLoc.value = false;
    toBottom();
    await send(props.threadId, { location: { lat: Math.round(p.lat * 1e6) / 1e6, lng: Math.round(p.lng * 1e6) / 1e6 } });
  } catch {
    locErr.value = T.locFailed();
  } finally {
    locBusy.value = false;
  }
}

// ---------- মেসেজের কাজ ----------
const selected = ref<string | null>(null);
const select = (m: ChatMessage) => (selected.value = selected.value === m.id ? null : m.id);
async function copy(m: ChatMessage) {
  try {
    await navigator.clipboard.writeText(m.text || '');
    toast(T.copied());
  } catch {
    /* কপি চলে না */
  }
  selected.value = null;
}
const unsending = ref<ChatMessage | null>(null);
const unsendBusy = ref(false);
const unsendErr = ref<string | null>(null);
async function doUnsend() {
  const m = unsending.value;
  if (!m) return;
  unsendBusy.value = true;
  unsendErr.value = null;
  try {
    await unsend(props.threadId, m);
    unsending.value = null;
    selected.value = null;
  } catch (e) {
    unsendErr.value = errorText(e);
  } finally {
    unsendBusy.value = false;
  }
}
const reporting = ref<ChatMessage | null>(null);
const reason = ref<ReportReason>('harassment');
const note = ref('');
const repBusy = ref(false);
const repErr = ref<string | null>(null);
function openReport(m: ChatMessage) {
  reporting.value = m;
  reason.value = 'harassment';
  note.value = '';
  repErr.value = null;
}
async function doReport() {
  if (!reporting.value) return;
  repBusy.value = true;
  repErr.value = null;
  try {
    await report(reporting.value, reason.value, note.value.trim());
    reporting.value = null;
    selected.value = null;
    toast(T.reported());
  } catch (e) {
    repErr.value = errorText(e);
  } finally {
    repBusy.value = false;
  }
}
const blocking = ref(false);
const blockBusy = ref(false);
const blockErr = ref<string | null>(null);
async function doBlock() {
  blockBusy.value = true;
  blockErr.value = null;
  try {
    await block(props.threadId);
    blocking.value = false;
    toast(T.blocked());
  } catch (e) {
    blockErr.value = errorText(e);
  } finally {
    blockBusy.value = false;
  }
}
</script>

<template>
  <div :class="cx('chat-thread', two && 'two')" data-testid="thread-view" :data-thread="threadId">
    <header class="chat-head">
      <IconButton v-if="!two" icon="arrow_back" :label="tr('Back', 'পেছনে')" @click="nav('/messages')" />
      <Avatar :photo="info?.peer.photoThumb ? imgSrc(info.peer.photoThumb) : null" :size="40" />
      <div class="col grow chat-head-who">
        <h1 class="t-title-m ellipsis" data-testid="thread-peer">{{ peerName }}</h1>
        <span class="t-label-m muted ellipsis">{{ ctxText(info?.ctx ?? null) }}</span>
      </div>
      <ContactActions
        v-if="info && info.ctx && info.refId && canSend"
        class="chat-calls"
        :ctx="info.ctx as CallCtx"
        :ref-id="info.refId"
        :peer-uid="info.peer.uid || undefined"
        :peer-name="info.peer.name"
      />
      <IconButton v-if="info?.peer.uid && info.closed !== 'blocked'" icon="block" :label="T.block()" data-testid="thread-block" @click="blocking = true" />
    </header>

    <div ref="scroller" class="chat-scroll" data-testid="thread-scroll" @scroll.passive="onScroll">
      <div class="chat-msgs">
        <div v-if="chat.older[threadId]" class="chat-older">
          <Button variant="text" small :busy="olderBusy" data-testid="thread-older" @click="older">{{ T.older() }}</Button>
        </div>
        <Loading v-if="chat.loading[threadId] && !list.length" />
        <ErrorBox v-else-if="chat.error[threadId] && !list.length" :error="chat.error[threadId]!" :on-retry="() => loadLatest(threadId)" />
        <p v-else-if="!list.length" class="chat-first t-body-m muted">{{ T.firstMsg() }}</p>
        <template v-for="(m, i) in list" :key="m.id">
          <div v-if="dayOf(i)" class="chat-day"><span>{{ dayOf(i) }}</span></div>
          <MessageBubble
            :m="m"
            :mine="mine(m)"
            :selected="selected === m.id"
            :now="now"
            :can-report="!!info?.peer.uid"
            @select="select(m)"
            @photo="viewing = m"
            @photo-error="photoExpired"
            @retry="retry(threadId, m)"
            @discard="discard(threadId, m)"
            @copy="copy(m)"
            @unsend="unsending = m; unsendErr = null"
            @report="openReport(m)"
          />
        </template>
      </div>
    </div>
    <button v-if="newBelow" type="button" class="chat-newbelow btn tonal small" data-testid="thread-new" @click="toBottom">{{ T.newBelow() }}</button>

    <footer v-if="!canSend" class="chat-closed" data-testid="thread-readonly">
      <Icon name="lock" :size="20" />
      <div class="col">
        <span class="t-title-s">{{ info?.closed === 'blocked' ? T.blocked() : T.readOnly() }}</span>
        <span class="t-body-s muted">{{ closedText }}</span>
      </div>
    </footer>
    <footer v-else class="chat-compose">
      <ErrorText :text="sendErr" />
      <div class="chat-compose-row">
        <input ref="fileIn" type="file" accept="image/*" hidden data-testid="compose-file" @change="picked" />
        <IconButton v-if="featureOn('chat_media')" icon="image" :label="T.photo()" :disabled="photoBusy" data-testid="compose-photo" @click="fileIn?.click()" />
        <IconButton v-if="featureOn('chat_media')" icon="location_on" :label="T.location()" data-testid="compose-location" @click="askLoc = true; locErr = null" />
        <label class="chat-input">
          <span class="sr-only">{{ T.placeholder() }}</span>
          <textarea
            v-model="text"
            rows="1"
            :placeholder="T.placeholder()"
            :maxlength="TEXT_MAX + 200"
            data-testid="compose-text"
            @keydown="onKey"
          />
        </label>
        <IconButton icon="send" :label="T.send()" :disabled="!text.trim() || tooLong" data-testid="compose-send" @click="sendText" />
      </div>
      <span v-if="text.length > TEXT_MAX - 100" :class="cx('t-label-s chat-count', tooLong && 'err')">{{ tooLong ? T.tooLong(TEXT_MAX) : num(TEXT_MAX - text.length) }}</span>
      <span v-if="photoBusy" class="t-label-s muted">{{ T.sending() }}</span>
    </footer>

    <Dialog v-if="viewing" :title="T.photo()" size="wide" @close="viewing = null">
      <img class="chat-photo-full" :src="imgSrc(viewing.photoUrl || viewing.photoThumb || '')" alt="" @error="photoExpired" />
    </Dialog>
    <ConfirmDialog
      v-if="askLoc"
      :title="T.sendLocTitle()"
      :text="T.sendLocText()"
      :confirm="T.send()"
      :busy="locBusy"
      :error="locErr"
      @confirm="sendLocation"
      @close="askLoc = false"
    />
    <ConfirmDialog
      v-if="unsending"
      :title="T.unsendTitle()"
      :text="T.unsendText()"
      :confirm="T.unsend()"
      danger
      :busy="unsendBusy"
      :error="unsendErr"
      @confirm="doUnsend"
      @close="unsending = null"
    />
    <ConfirmDialog
      v-if="blocking"
      :title="T.blockTitle(peerName)"
      :text="T.blockText()"
      :confirm="T.block()"
      danger
      :busy="blockBusy"
      :error="blockErr"
      @confirm="doBlock"
      @close="blocking = false"
    />
    <Dialog v-if="reporting" :title="T.reportTitle()" size="small" @close="() => !repBusy && (reporting = null)">
      <div class="col gap-12" data-testid="report-dialog">
        <p v-if="reporting.text" class="t-body-m chat-report-quote">{{ reporting.text }}</p>
        <ChoiceChips v-model="reason" :options="REPORT_REASONS.map((r) => ({ value: r, label: reasonText(r) }))" />
        <TextArea v-model="note" :label="T.reportNote()" maxlength="300" />
        <ErrorText :text="repErr" />
        <div class="row">
          <span class="grow" />
          <Button variant="text" :disabled="repBusy" @click="reporting = null">{{ tr('Cancel', 'বাতিল') }}</Button>
          <Button :busy="repBusy" data-testid="report-send" @click="doReport">{{ T.reportSend() }}</Button>
        </div>
      </div>
    </Dialog>
  </div>
</template>
