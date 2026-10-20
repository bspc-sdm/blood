<script setup lang="ts">
// হোমে "আমার রিকোয়েস্ট": নিজের খোলা রিকোয়েস্টের অবস্থা
import { computed, ref } from 'vue';
import { ErrorText, Icon, InfoText } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { useQ } from '@/lib/query';
import { KEY, api, myRequests, responses } from './data';
import { canReopen, deadline, isActive, type BloodRequest, type DonorResponse } from './model';
import { useRunner } from './runner';
import UndoNotice from './parts/UndoNotice.vue';
import RemovedNotice from './parts/RemovedNotice.vue';
import CloseRequestDialog from './parts/CloseRequestDialog.vue';
import ShareDialog from './parts/ShareDialog.vue';
import MyRequestCard from './MyRequestCard.vue';

const s = useSession();
const nav = useNav();
const { busy, error, run } = useRunner();
const info = ref<string | null>(null);
const closing = ref<{ r: BloodRequest; list: DonorResponse[] } | null>(null);
const removed = ref<{ rid: string; d: DonorResponse } | null>(null);
const hidden = ref<string[]>([]);
const sharing = ref<BloodRequest | null>(null);
const q = useQ<{ items: { r: BloodRequest; list: DonorResponse[] }[]; recentClosed: BloodRequest[] }>(() => ({
  queryKey: [KEY, 'homeMine', s.uid],
  queryFn: async () => {
    const mine = await myRequests(s.uid!);
    const now = Date.now();
    const recentClosed = mine.filter((r) => canReopen(r, now));
    const active = mine.filter((r) => isActive(r, now)).sort((a, b) => deadline(a) - deadline(b));
    const items = await Promise.all(active.map(async (r) => ({ r, list: await responses(r.id).catch(() => [] as DonorResponse[]) })));
    return { items, recentClosed };
  },
  enabled: !!s.uid,
  staleTime: 10_000,
}));
const reopenable = computed(() => (q.data ? q.data.recentClosed.filter((r) => !hidden.value.includes(r.id)) : []));
const show = computed(() => !!s.uid && !!q.data && (q.data.items.length > 0 || reopenable.value.length > 0));

async function undoRemove() {
  const x = removed.value!;
  if (await run(x.rid, () => api.updateResponse(x.rid, 'restore', x.d.donorUid))) removed.value = null;
}
function notifyAgain(r: BloodRequest) {
  run(r.id, async () => {
    const n = await api.notify(r.id, true);
    info.value =
      n > 0
        ? tr(`${num(n)} more donors were notified`, `আরও ${num(n)} জন ডোনারকে জানানো হয়েছে`)
        : tr('No new donors found nearby right now. Please share the request.', 'এই মুহূর্তে কাছে নতুন ডোনার পাওয়া যায়নি। রিকোয়েস্টটা শেয়ার করুন।');
  });
}
async function remove(r: BloodRequest, d: DonorResponse) {
  if (await run(r.id, () => api.updateResponse(r.id, 'remove', d.donorUid))) removed.value = { rid: r.id, d };
}
function closeReq(chosen: string[], got: number) {
  const id = closing.value!.r.id;
  closing.value = null;
  run(id, () => api.closeRequest(id, chosen, got));
}
</script>

<template>
  <section v-if="show && q.data" class="blood-mine col" :aria-label="tr('My Request', 'আমার রিকোয়েস্ট')">
    <div class="row gap-8">
      <Icon name="bloodtype" :size="20" class="c-primary" />
      <h2 class="t-title-m bold">{{ tr('My Request', 'আমার রিকোয়েস্ট') }}</h2>
    </div>
    <ErrorText :text="error" />
    <InfoText :text="info" />
    <UndoNotice
      v-for="c in reopenable"
      :key="c.id"
      :text="tr(`Request at ${c.hospital} closed`, `${c.hospital}-এর রিকোয়েস্ট বন্ধ হয়েছে`)"
      :undo="tr('Open again', 'আবার খুলুন')"
      :busy="busy === c.id"
      @undo="run(c.id, () => api.reopen(c.id))"
      @dismiss="hidden = [...hidden, c.id]"
    />
    <RemovedNotice v-if="removed" :name="removed.d.donorName" :busy="busy === removed.rid" @undo="undoRemove" @dismiss="removed = null" />
    <MyRequestCard
      v-for="{ r, list } in q.data.items"
      :key="r.id"
      :r="r"
      :list="list"
      :busy-id="busy"
      @share="sharing = r"
      @notify="run(r.id, () => api.notify(r.id))"
      @notify-again="notifyAgain(r)"
      @confirm="(d) => run(r.id, () => api.confirmDonor(r.id, d.donorUid))"
      @remove="(d) => remove(r, d)"
      @open="nav(`/my-requests/${r.id}`)"
      @close="closing = { r, list }"
    />
    <CloseRequestDialog v-if="closing" :bags="closing.r.bags" :already="closing.r.bagsReceived" :list="closing.list" @dismiss="closing = null" @close="closeReq" />
    <ShareDialog v-if="sharing" :r="sharing" @close="sharing = null" />
  </section>
</template>
