<script setup lang="ts">
import { computed, ref } from 'vue';
import { Action, ConfirmDialog, ErrorText, Icon, IconButton, InfoText, Loading } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { num, tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { api, useMyRequest } from './data';
import { canReopen, counts, isActive, willing, type DonorResponse } from './model';
import { useRunner } from './runner';
import RequestInfo from './parts/RequestInfo.vue';
import RequestProgress from './parts/RequestProgress.vue';
import Note from './parts/Note.vue';
import RemovedNotice from './parts/RemovedNotice.vue';
import DonorRow from './parts/DonorRow.vue';
import CloseRequestDialog from './parts/CloseRequestDialog.vue';
import ShareDialog from './parts/ShareDialog.vue';

const props = defineProps<{ id: string }>();
const nav = useNav();
const q = useMyRequest(() => props.id);
const { busy, error, run } = useRunner();
const info = ref<string | null>((window.history.state as { info?: string } | null)?.info ?? null);
const closing = ref(false);
const deleting = ref(false);
const delBusy = ref(false);
const removed = ref<DonorResponse | null>(null);
const sharing = ref(false);
const r = computed(() => q.data?.r ?? null);
const list = computed(() => q.data?.responses ?? []);
const active = computed(() => !!r.value && isActive(r.value));
const w = computed(() => willing(list.value));
const isBusy = computed(() => busy.value != null);
const sorted = computed(() => [...w.value].sort((a, b) => Number(counts(b)) - Number(counts(a))));
const willingCount = computed(() => w.value.length);

function notify() {
  const x = r.value!;
  run('notify', async () => {
    const n = await api.notify(x.id);
    info.value = tr(`${num(n)} donors notified`, `${num(n)} জন ডোনারকে জানানো হয়েছে`);
  });
}
async function restore() {
  const x = r.value!;
  if (await run('restore', () => api.updateResponse(x.id, 'restore', removed.value!.donorUid))) removed.value = null;
}
async function removeDonor(d: DonorResponse) {
  const x = r.value!;
  if (await run('remove', () => api.updateResponse(x.id, 'remove', d.donorUid))) removed.value = d;
}
async function doDelete() {
  delBusy.value = true;
  error.value = null;
  if (await run('delete', () => api.deleteRequest(props.id))) {
    deleting.value = false;
    nav('/my-requests', { replace: true });
  } else deleting.value = false;
  delBusy.value = false;
}
function closeReq(chosen: string[], got: number) {
  closing.value = false;
  run('close', () => api.closeRequest(props.id, chosen, got));
}
const deleteText = computed(() =>
  active.value && willingCount.value > 0
    ? tr(
      `It will be removed for everyone. The ${num(willingCount.value)} donor(s) who agreed will be told they don't need to come.`,
      `সবার কাছ থেকে সরে যাবে। রাজি হওয়া ${num(willingCount.value)} জন ডোনারকে জানানো হবে যে আর আসার দরকার নেই।`,
    )
    : active.value
      ? tr('It will be removed for everyone and donors will stop seeing it.', 'সবার কাছ থেকে সরে যাবে, ডোনাররা আর দেখবেন না।')
      : tr("It will be removed from your list. This can't be undone.", 'আপনার তালিকা থেকে মুছে যাবে। আর ফেরানো যাবে না।'),
);
</script>

<template>
  <DetailPane :title="tr('My Request', 'আমার রিকোয়েস্ট')" back-to="/my-requests">
    <template #actions>
      <IconButton v-if="r && active" icon="share" :label="tr('Share', 'শেয়ার')" @click="sharing = true" />
      <IconButton v-if="r && active" icon="edit" :label="tr('Edit', 'এডিট')" :disabled="isBusy" @click="nav(`/my-requests/${id}/edit`)" />
      <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="q.refetch()" />
      <IconButton v-if="r" icon="delete" :label="tr('Delete', 'মুছে ফেলুন')" :disabled="isBusy" @click="deleting = true" />
    </template>
    <Loading v-if="q.isLoading" />
    <ErrorText v-else-if="!r" :text="q.error ? tr('Couldn\'t load', 'লোড হয়নি') : tr('Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি')" />
    <template v-else>
      <RequestInfo :r="r" />
      <div v-if="active && r.notified" class="icon-line">
        <Icon name="notifications_active" />
        <span>
          {{ r.notifiedCount > 0
            ? tr(`${num(r.notifiedCount)} nearby donors notified`, `আশেপাশের ${num(r.notifiedCount)} জন ডোনারকে জানানো হয়েছে`)
            : tr('No donors with notifications on nearby yet', 'এখনো আশেপাশে নোটিফিকেশন চালু থাকা ডোনার পাওয়া যায়নি') }}
        </span>
      </div>
      <Action v-if="active && !r.notified" icon="notifications" primary :busy="busy === 'notify'" :disabled="isBusy" :style="{ alignSelf: 'flex-start' }" @click="notify">
        {{ tr('Notify nearby donors', 'আশেপাশের ডোনারদের জানান') }}
      </Action>
      <InfoText :text="info" />
      <RequestProgress v-if="active" :r="r" :list="list" />
      <h3 class="t-title-s c-primary blood-sectitle">{{ tr(`Donors (${num(w.length)})`, `রাজি হয়েছেন (${num(w.length)})`) }}</h3>
      <Note v-if="!w.length">{{ tr('No one has responded yet.', 'এখনো কেউ সাড়া দেননি।') }}</Note>
      <Note v-else-if="active">{{ tr('Confirm the donors you\'ll take. Others are told when enough are confirmed.', 'যাঁদের নেবেন তাঁদের নিশ্চিত করুন। যথেষ্ট নিশ্চিত হলে বাকিদের জানানো হয়।') }}</Note>
      <RemovedNotice v-if="removed" :name="removed.donorName" :busy="busy === 'restore'" @undo="restore" @dismiss="removed = null" />
      <div v-for="d in sorted" :key="d.donorUid" class="card" :style="{ padding: '12px' }">
        <DonorRow
          :d="d"
          :req-id="r.id"
          :active="active"
          :busy="isBusy"
          :can-confirm="r.confirmedCount < r.bags"
          @confirm="run('confirm', () => api.confirmDonor(r!.id, d.donorUid))"
          @remove="removeDonor(d)"
        />
      </div>
      <ErrorText :text="error" />
      <Action v-if="canReopen(r)" icon="refresh" :busy="busy === 'reopen'" :style="{ alignSelf: 'flex-start' }" @click="run('reopen', () => api.reopen(r!.id))">
        {{ tr('Closed by mistake? Open again', 'ভুল করে বন্ধ? আবার খুলুন') }}
      </Action>
      <div v-if="active" class="row" :style="{ justifyContent: 'flex-end' }">
        <Action icon="check_circle" primary :disabled="isBusy" @click="closing = true">{{ tr('Got blood, close', 'রক্ত পেয়েছি, বন্ধ করুন') }}</Action>
      </div>
    </template>
    <ConfirmDialog
      v-if="deleting"
      :title="tr('Delete this request?', 'রিকোয়েস্টটা মুছে ফেলবেন?')"
      :text="deleteText"
      :confirm="tr('Delete', 'মুছে ফেলুন')"
      danger
      :busy="delBusy"
      @close="deleting = false"
      @confirm="doDelete"
    />
    <CloseRequestDialog v-if="closing && r" :bags="r.bags" :already="r.bagsReceived" :list="list" @dismiss="closing = false" @close="closeReq" />
    <ShareDialog v-if="sharing && r" :r="r" @close="sharing = false" />
  </DetailPane>
</template>
