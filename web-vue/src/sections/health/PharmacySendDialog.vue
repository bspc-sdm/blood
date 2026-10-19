<script setup lang="ts">
// ফার্মেসির পাতা থেকে: আগে এই দোকানের হিসাব (কয়টা ওষুধ আছে, কোনটা নেই, আনুমানিক খরচ), তারপর দোকানে গিয়ে নেব (সাথে সাথে পাঠানো),
// অথবা — দোকান অনলাইনে অর্ডার নিলে — অনলাইনে অর্ডার।
// rx_pharmacy_options-এ placeId: শুধু এই দোকান (দূরে হলেও) — অনলাইনে নেয় কিনা, ডেলিভারি চার্জ, স্টক আর দাম একসাথে আসে।
import { computed, ref } from 'vue';
import { Button, Dialog, IconLine, ListRow, Loading } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { useCareMode } from '@/lib/care';
import type { LastHome, PlacePharmacy, Rx } from './rx';
import { pharmEstimateText, pharmHereText, type PharmOption } from './rxactivity';
import SendError from './SendError.vue';
import OnlineOrderDialog from './OnlineOrderDialog.vue';

const props = defineProps<{ rx: Rx; p: PlacePharmacy; mode?: 'shop' | 'online'; last?: LastHome | null }>();
const emit = defineEmits<{ close: []; sent: [mode: 'shop' | 'online'] }>();
const care = useCareMode();
const busy = ref(false);
const err = ref<string | null>(null);
const ordering = ref<PharmOption | null>(null);
const q = useQ<PharmOption | null>(() => ({
  queryKey: ['rx_pharmacy_options', props.rx.id, 'place', props.p.district, null, '', null, null, props.p.id, care.value?.uid ?? null],
  queryFn: () =>
    call<{ items?: PharmOption[] }>('rx_pharmacy_options', { rxId: props.rx.id, district: props.p.district, mode: 'shop', placeId: props.p.id })
      .then((r) => (r.items ?? []).find((x) => x.id === props.p.id) ?? null),
  staleTime: 0,
}));
async function shop() {
  if (busy.value) return;
  busy.value = true;
  err.value = null;
  try {
    await call('rx_send_pharmacy', { rxId: props.rx.id, pharmacyId: props.p.id, mode: 'shop' });
    emit('sent', 'shop');
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
const here = computed(() => q.data ?? null);
const online = computed(() => (here.value?.online ? here.value : null));
const rxText = computed(() => [props.rx.doctor?.name || tr('Doctor', 'ডাক্তার'), formatDate(props.rx.createdAt)].join(' · '));
</script>

<template>
  <!-- তালিকায় "অনলাইনে অর্ডার" বাছা: সোজা অর্ডারের ফর্ম (দোকান অনলাইনে না নিলে জানানো) -->
  <template v-if="mode === 'online'">
    <OnlineOrderDialog v-if="online" :rx="rx" :p="online" :last="last" @close="emit('close')" @sent="emit('sent', 'online')" />
    <Dialog v-else size="small" :title="p.name" @close="emit('close')">
      <Loading v-if="q.isPending" />
      <SendError v-else :text="q.isError ? errorText(q.error) : tr('This pharmacy doesn\'t take online orders now', 'এই ফার্মেসি এখন অনলাইনে অর্ডার নেয় না')" />
      <template #actions><Button variant="text" @click="emit('close')">{{ tr('Close', 'বন্ধ') }}</Button></template>
    </Dialog>
  </template>
  <!-- তালিকায় "দোকানে গিয়ে কিনব" বাছা: এই দোকানের হিসাব দেখে এক চাপে পাঠানো -->
  <Dialog v-else-if="mode === 'shop'" size="small" :title="p.name" @close="!busy && emit('close')">
    <div class="col gap-4">
      <IconLine icon="medication" color="var(--on-surface)">{{ rxText }}</IconLine>
      <template v-if="here">
        <IconLine :icon="here.keepsStock ? 'check_circle' : 'info'">{{ pharmHereText(here) }}</IconLine>
        <IconLine icon="payments">{{ pharmEstimateText(here.estimate) }}</IconLine>
      </template>
      <span v-else-if="q.isPending" class="t-body-s muted">{{ tr('Checking this shop…', 'এই দোকানের হিসাব দেখা হচ্ছে…') }}</span>
      <IconLine icon="storefront" color="var(--on-surface)">{{ tr("You'll collect at the shop", 'দোকানে গিয়ে নেবেন') }}</IconLine>
    </div>
    <SendError :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button icon="send" :busy="busy" data-testid="ph-send-shop" @click="shop">{{ tr('Send to the shop', 'দোকানে পাঠান') }}</Button>
    </template>
  </Dialog>
  <Dialog v-else size="small" :title="p.name" @close="!busy && emit('close')">
    <!-- পাঠানোর আগে এই দোকানের হিসাব -->
    <div v-if="here" class="col gap-4">
      <IconLine :icon="here.keepsStock ? 'check_circle' : 'info'">{{ pharmHereText(here) }}</IconLine>
      <IconLine icon="payments">{{ pharmEstimateText(here.estimate) }}</IconLine>
    </div>
    <div class="col gap-4">
      <ListRow icon="storefront" :title="tr('I\'ll go to the shop', 'দোকানে গিয়ে নেব')" @click="shop">
        <template v-if="busy" #trailing><span class="spinner small" /></template>
      </ListRow>
      <span v-if="q.isPending" class="t-body-s muted">{{ tr('Checking this shop…', 'এই দোকানের হিসাব দেখা হচ্ছে…') }}</span>
      <ListRow
        v-else-if="online"
        icon="local_shipping"
        :title="tr('Order online — home delivery', 'অনলাইনে অর্ডার — বাসায় ডেলিভারি')"
        :sub="online.deliveryFee ? tr('Delivery: ', 'ডেলিভারি: ') + online.deliveryFee : undefined"
        @click="!busy && (ordering = online)"
      />
    </div>
    <SendError :text="err" />
    <OnlineOrderDialog v-if="ordering" :rx="rx" :p="ordering" :last="last" @close="ordering = null" @sent="emit('sent', 'online')" />
  </Dialog>
</template>
