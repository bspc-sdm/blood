<script setup lang="ts">
// /work/rx/write — রোগী বাছা না থাকলে রোগী বাছাই, থাকলে লেখার ফর্ম
import { computed } from 'vue';
import { SkeletonCards } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useNav, useSearchParams } from '@/lib/nav';
import { findTarget, targetKey, targetSearch, type Actor, type RxPatient, type RxTarget } from './model';
import { usePatients, type Link } from './patients';
import PatientsList from './PatientsList.vue';
import RxEditor from './RxEditor.vue';

import type { RxKind } from '@/lib/practice';

const props = defineProps<{ actor: Actor; chambers: string[]; degrees: string; link: Link; kind?: RxKind; headTag?: string | null }>();
const nav = useNav();
const [sp] = useSearchParams();
const pq = usePatients(() => props.actor);
const target = computed(() => findTarget(sp.value, pq.data));
const write = (t: RxTarget) => nav(props.link('/write', targetSearch(t)));
const added = (p: RxPatient) => write({ rxPatientId: p.id, name: p.name });
</script>

<template>
  <div v-if="!target" class="col gap-12">
    <h2 class="t-title-l">{{ tr('Who is it for?', 'কার জন্য লিখবেন?') }}</h2>
    <PatientsList :actor="actor" :query="pq" pick :on-write="write" :on-added="added" />
  </div>
  <!-- নাম-বয়স তালিকা থেকে (না পেলে সার্ভার নিজের কাছের তথ্য বসায়) -->
  <SkeletonCards v-else-if="pq.isPending" :n="3" :h="140" />
  <RxEditor
    v-else
    :key="targetKey(target)"
    :target="target"
    :chambers="chambers"
    :doctor-name="actor.doctorName"
    :degrees="degrees"
    :kind="kind"
    :head-tag="headTag"
    :link="link"
    @other-patient="nav(link('/write'))"
  />
</template>
