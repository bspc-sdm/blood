<script setup lang="ts">
// রোগী: আজ-কালকের অ্যাপয়েন্টমেন্ট, এন্ট্রি করা রোগী, অ্যাপে আগে আসা রোগী — খোঁজা, নতুন যোগ, লেখা, আগের প্রেসক্রিপশন।
// অ্যাপের RxPatientsPane / PatientRow / RxPatientAddDialog / PatientHistoryDialog; প্যানেলের patientsView/historyView।
import { computed } from 'vue';
import { useMedia } from '@/shell/chrome';
import { tr } from '@/lib/i18n';
import { useNav, useSearchParams } from '@/lib/nav';
import { findTarget, targetKey, targetSearch, type Actor, type RxPatient, type RxTarget } from './model';
import { usePatients, type Link } from './patients';
import PatientsList from './PatientsList.vue';
import HistoryPane from './HistoryPane.vue';
import QueuePanel from '../../appointment/QueuePanel.vue';

const props = defineProps<{ actor: Actor; link: Link }>();
const nav = useNav();
const [sp] = useSearchParams();
const wide = useMedia('(min-width: 1100px)');
const q = usePatients(() => props.actor);
const doctor = computed(() => props.actor.role === 'doctor');
const target = computed(() => (doctor.value ? findTarget(sp.value, q.data) : null));
const select = (t: RxTarget | null) => nav(props.link('', t ? targetSearch(t) : {}), { replace: !!t && !!target.value });
const write = (t: RxTarget) => nav(props.link('/write', targetSearch(t)));
function added(p: RxPatient) {
  if (doctor.value) write({ rxPatientId: p.id, name: p.name, age: p.age, gender: p.gender });
}
</script>

<template>
  <!-- সহকারী: আজকের সিরিয়াল চালান (ডাক্তার নিজে "আমার চেম্বার › বুকিং"-এ) -->
  <div v-if="!doctor" class="col gap-16">
    <QueuePanel :doctor-uid="actor.doctorUid" />
    <PatientsList :actor="actor" :query="q" :selected-key="null" :on-added="added" />
  </div>
  <PatientsList v-else-if="!target" :actor="actor" :query="q" :selected-key="null" :on-open="select" :on-write="write" :on-added="added" />
  <HistoryPane v-else-if="!wide" :key="targetKey(target)" :target="target" :link="link" :wide="wide" @close="select(null)" @write="write(target!)" />
  <div v-else class="rxd-split">
    <div class="rxd-split-main">
      <PatientsList :actor="actor" :query="q" :selected-key="targetKey(target)" :on-open="select" :on-write="write" :on-added="added" />
    </div>
    <aside class="rxd-split-side" :aria-label="tr('Earlier prescriptions', 'আগের প্রেসক্রিপশন')">
      <HistoryPane :key="targetKey(target)" :target="target" :link="link" :wide="wide" @close="select(null)" @write="write(target!)" />
    </aside>
  </div>
</template>
