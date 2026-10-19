<script setup lang="ts">
// প্যাথলজি/ফার্মেসির পাতা থেকেই প্রেসক্রিপশন পাঠানো (অ্যাপের RxSendFlow): আগে কোন প্রেসক্রিপশন (চালু — প্যাথলজিতে পরীক্ষা লেখা,
// ফার্মেসিতে ওষুধ লেখা), তারপর আগের পথই: প্যাথলজি → LabPickDialog (শুধু এই প্যাথলজি); ফার্মেসি → PharmacySendDialog।
// অতিথি হলে আগে লগইন; চালু প্রেসক্রিপশন একটাই হলে "কোনটা পাঠাবেন?" না দেখিয়ে সেটাই; পাঠানোর পর RxSentDialog।
// ফার্মেসির তালিকা থেকে (২.৩২.০): mode — উপরে বাছা উপায়; rx — তালিকার উপরে বাছা প্রেসক্রিপশন; card — কার্ডের ভেতরের বাটন (চাপ কার্ডে যায় না)।
import { ref } from 'vue';
import { Button } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { queryClient } from '@/lib/query';
import type { LastHome, PlaceLab, PlacePharmacy, Rx } from './rx';
import type { RxSent, SentMode } from './rxactivity';
import RxPickDialog from './RxPickDialog.vue';
import LabPickDialog from './LabPickDialog.vue';
import PharmacySendDialog from './PharmacySendDialog.vue';
import RxSentDialog from './RxSentDialog.vue';

const props = defineProps<{ lab?: PlaceLab; pharmacy?: PlacePharmacy; mode?: 'shop' | 'online'; rx?: Rx | null; last?: LastHome | null; card?: boolean }>();
const s = useSession();
const nav = useNav();
const picking = ref(false);
const rx = ref<Rx | null>(null);
// একটাই প্রেসক্রিপশন বলে নিজে বাছা — তখন পরের ধাপ থেকে পেছনে গেলে পুরোটা বন্ধ (আবার নিজে বেছে ফেলত)
const auto = ref(false);
const sent = ref<RxSent | null>(null);
const close = () => {
  rx.value = null;
  picking.value = false;
  auto.value = false;
};
const back = () => (auto.value ? close() : (rx.value = null));
const done = (place: string, mode: SentMode) => {
  const r = rx.value;
  if (r) {
    queryClient.invalidateQueries({ queryKey: ['rx_activity', r.id] });
    sent.value = { rxId: r.id, doctorName: r.doctor?.name ?? '', date: r.createdAt, place, mode };
  }
  close();
};
const start = () =>
  s.requireLogin(tr('Log in to send a prescription', 'প্রেসক্রিপশন পাঠাতে লগইন করুন'), () => {
    // তালিকায় বাছা প্রেসক্রিপশন: সরাসরি সেটাই (পেছনে গেলে পুরোটা বন্ধ)
    if (props.rx) {
      auto.value = true;
      rx.value = props.rx;
    }
    picking.value = true;
  });
const onOnly = (r: Rx) => {
  auto.value = true;
  rx.value = r;
};
// প্রেসক্রিপশনের পাতা (Prescriptions-এর পথ) — দোকান/প্যাথলজির বিস্তারিত পথ বদলালেই বন্ধ; কেয়ারগিভার মোড এই পথে থাকে
function openRx() {
  const id = sent.value!.rxId;
  sent.value = null;
  nav(`/prescriptions/${id}`);
}
</script>

<template>
  <!-- কার্ডের ভেতরে: চাপ কার্ডে গিয়ে বিস্তারিত খোলে না (Vue-তে ডায়ালগ body-তে, তাই ভেতরের চাপ কার্ডে পৌঁছায় না) -->
  <span v-if="card" class="contents" @click.stop @keydown.stop>
    <Button icon="send" square class="ph-card-send" data-testid="ph-card-send" @click="start">{{ tr('Send prescription', 'প্রেসক্রিপশন পাঠান') }}</Button>
  </span>
  <Button v-else icon="send" class="detail-bar-main" @click="start">{{ tr('Send prescription', 'প্রেসক্রিপশন পাঠান') }}</Button>
  <RxPickDialog v-if="picking && !rx" :for-lab="!!lab" :on-only="onOnly" @close="close" @pick="(r) => (rx = r)" />
  <LabPickDialog v-if="rx && lab" :rx="rx" :only="lab" @close="back" @sent="(name, home) => done(name, home ? 'lab_home' : 'lab')" />
  <PharmacySendDialog v-if="rx && pharmacy" :rx="rx" :p="pharmacy" :mode="mode" :last="last" @close="back" @sent="(m) => done(pharmacy!.name, m)" />
  <RxSentDialog v-if="sent" :s="sent" @close="sent = null" @open="openRx" />
</template>
