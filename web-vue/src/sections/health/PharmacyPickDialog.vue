<script setup lang="ts">
// ফার্মেসি বাছাই (দোকানে আছি / অনলাইনে অর্ডার)
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Button, Dialog, DirectoryCard, EmptyState, ErrorText, FilterBar, Loading, TextInput } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import type { AreaValue } from '@/ui/areaFilter';
import { call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatKm } from '@/lib/format';
import { areaLabel } from '@/lib/geo';
import { useSession } from '@/lib/session';
import type { Rx } from './rx';
import { callTel, pharmEstimateText, startArea, type PharmOption } from './rxactivity';
import SendError from './SendError.vue';
import OnlineOrderDialog from './OnlineOrderDialog.vue';

const props = defineProps<{ rx: Rx; mode: 'shop' | 'online' }>();
const emit = defineEmits<{ close: []; sent: [name: string] }>();
const s = useSession();
const area = ref<AreaValue | null>(startArea());
const showArea = ref(false);
const here = ref<{ lat: number; lng: number } | null>(null);
const located = ref(props.mode !== 'shop');
const name = ref('');
const nameQ = ref('');
const shopCode = ref('');
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const ordering = ref<PharmOption | null>(null);

// দোকানে দাঁড়িয়ে: অনুমতি আগেই দেওয়া থাকলে এখনকার লোকেশন (নতুন করে অনুমতি চাওয়া নয়)
let off = false;
onMounted(() => {
  if (located.value) return;
  const done = () => {
    if (!off) located.value = true;
  };
  const perms = navigator.permissions;
  if (!perms || !navigator.geolocation) {
    done();
    return;
  }
  perms
    .query({ name: 'geolocation' as PermissionName })
    .then((p) => {
      if (p.state !== 'granted') return done();
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          if (!off) here.value = { lat: pos.coords.latitude, lng: pos.coords.longitude };
          done();
        },
        done,
        { timeout: 8000, maximumAge: 60000 },
      );
    })
    .catch(done);
});
onBeforeUnmount(() => {
  off = true;
});
watch(name, (n, _o, onCleanup) => {
  const t = setTimeout(() => (nameQ.value = n.trim()), n.trim() ? 400 : 0);
  onCleanup(() => clearTimeout(t));
});

const loc = computed(() => {
  const p = s.profile;
  const gps = p?.locationSource === 'gps' && p.lat != null && p.lng != null ? { lat: p.lat, lng: p.lng } : null;
  return here.value ?? gps;
});
const q = useQ<PharmOption[]>(() => ({
  queryKey: ['rx_pharmacy_options', props.rx.id, props.mode, area.value?.district, area.value?.upazilaId ?? null, nameQ.value, loc.value?.lat ?? null, loc.value?.lng ?? null],
  queryFn: () =>
    call<{ items?: PharmOption[] }>('rx_pharmacy_options', {
      rxId: props.rx.id, district: area.value!.district, mode: props.mode, q: nameQ.value,
      ...(area.value?.upazilaId ? { upazilaId: area.value.upazilaId } : {}),
      ...(loc.value ? { lat: loc.value.lat, lng: loc.value.lng } : {}),
    }).then((r) => r.items ?? []),
  enabled: !!area.value && located.value,
  staleTime: 0,
}));

async function send(id: string, nm: string) {
  if (busy.value) return;
  busy.value = id;
  err.value = null;
  try {
    await call('rx_send_pharmacy', { rxId: props.rx.id, pharmacyId: id, mode: 'shop' });
    emit('sent', nm);
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = null;
}
async function sendByCode() {
  busy.value = 'code';
  err.value = null;
  try {
    const r = await call<{ item: { id: string; name: string } }>('rx_shop_find', { code: shopCode.value });
    await call('rx_send_pharmacy', { rxId: props.rx.id, pharmacyId: r.item.id, mode: 'shop' });
    emit('sent', r.item.name);
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = null;
}
const online = computed(() => props.mode === 'online');
function onOrdered() {
  const nm = ordering.value!.name;
  ordering.value = null;
  emit('sent', nm);
}
</script>

<template>
  <Dialog size="wide" :title="online ? tr('Order online', 'অনলাইনে অর্ডার') : tr('Send to this shop', 'এই দোকানে দিন')" @close="!busy && emit('close')">
    <template v-if="!online">
      <TextInput v-model="name" :label="tr('Type the shop\'s name', 'দোকানের নাম লিখুন')" maxlength="60" type="search" />
      <div class="row top health-code-row">
        <TextInput
          :label="tr('Or the shop code', 'অথবা দোকানের কোড')"
          :model-value="shopCode"
          autocomplete="off"
          :spellcheck="false"
          class="input health-code"
          @update:model-value="(v: string) => (shopCode = v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6))"
        />
        <Button variant="outline" square :busy="busy === 'code'" :disabled="!!busy || shopCode.length !== 6" @click="sendByCode">{{ tr('Send', 'পাঠান') }}</Button>
      </div>
    </template>
    <p v-else class="t-body-s muted">{{ tr("Only pharmacies that deliver. You pay the shop when the medicines arrive — Lifolo doesn't take money.", 'শুধু যাঁরা বাসায় পৌঁছে দেন। ওষুধ পৌঁছালে দোকানকে দাম দেবেন — Lifolo টাকা নেয় না।') }}</p>
    <FilterBar :area-label="area ? areaLabel(area.district, area.upazilaId) : ''" :on-area="() => (showArea = true)" />
    <span v-if="!online" class="t-label-l">{{ nameQ ? tr('Shops with this name', 'এই নামের দোকান') : tr('Shops near you', 'কাছের দোকান') }}</span>
    <SendError :text="err" />
    <div class="col gap-12">
      <EmptyState v-if="!area" icon="location_on" :title="tr('Choose area', 'এলাকা বেছে নিন')" />
      <Loading v-else-if="q.isPending" />
      <ErrorText v-else-if="q.isError" :text="errorText(q.error)" />
      <EmptyState
        v-else-if="!q.data!.length"
        :icon="online ? 'local_shipping' : 'storefront'"
        :title="online ? tr('No pharmacy here sells online yet', 'এখানে এখনো কোনো ফার্মেসি অনলাইনে বিক্রি করে না') : nameQ ? tr('No shop with this name in the app', 'এই নামে অ্যাপে কোনো দোকান নেই') : tr('No pharmacy in the app here yet', 'এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই')"
        :text="online ? tr('Go to a pharmacy and show the QR.', 'ফার্মেসিতে গিয়ে QR দেখিয়ে কিনুন।') : tr('Show the QR at the counter instead.', 'কাউন্টারে QR দেখিয়েও কিনতে পারেন।')"
      />
      <template v-else>
        <DirectoryCard
          v-for="p in q.data"
          :key="p.id"
          :title="p.name"
          :icon="online ? 'local_shipping' : 'storefront'"
          :status="p.openNow ? { text: tr('Open', 'খোলা'), good: true } : { text: tr('Closed', 'বন্ধ'), good: false }"
          :meta="p.km != null ? formatKm(p.km) : null"
          :subtitle="p.keepsStock ? tr(`Has ${p.have} of ${p.total} medicines`, `${num(p.total)}টার ${num(p.have)}টা ওষুধ আছে`) : tr('Stock not listed', 'স্টকের তালিকা নেই')"
          :details="[
            ['location_on', p.address],
            ['local_shipping', online && p.deliveryFee ? tr('Delivery: ', 'ডেলিভারি: ') + p.deliveryFee : ''],
            ['info', online ? p.onlineNote : ''],
            ['warning', p.keepsStock && p.missing.length ? tr('Missing: ', 'নেই: ') + p.missing.join(', ') : ''],
            ['payments', pharmEstimateText(p.estimate)],
          ]"
          :primary="{ label: online ? tr('Order', 'অর্ডার করুন') : tr('Send here', 'এই দোকানে দিন'), icon: online ? 'local_shipping' : 'send', busy: busy === p.id, onClick: () => (online ? (ordering = p) : send(p.id, p.name)) }"
          :secondary="p.phone ? { label: tr('Call', 'ফোন'), icon: 'call', onClick: () => callTel(p.phone) } : null"
        />
      </template>
    </div>
    <AreaPicker v-if="showArea" :value="area" @close="showArea = false" @picked="(v) => { area = v; showArea = false; }" />
    <OnlineOrderDialog v-if="ordering" :rx="rx" :p="ordering" @close="ordering = null" @sent="onOrdered" />
  </Dialog>
</template>
