<script setup lang="ts">
// কার রসিদ: নিজের / যাঁদের দেখাশোনা করি (কেয়ারগিভার মোড — প্রেসক্রিপশনের মতোই উপরে ফিতা, "বন্ধ করুন")।
// শুধু যাঁর অন্তত একজন চালু রোগী আছে তাঁকেই দেখায়।
import { computed } from 'vue';
import { Icon } from '@/ui/kit';
import { useCall } from '@/lib/query';
import { setCareMode, useCareMode } from '@/lib/care';
import { useSession } from '@/lib/session';
import type { CareData } from '@/sections/account/care';
import { T } from './text';

const s = useSession();
const care = useCareMode();
const q = useCall<CareData>('care_list', {}, () => ({ enabled: !s.guest, staleTime: 60_000 }));
const people = computed(() => (q.data?.caringFor ?? []).filter((c) => c.status === 'active'));
const cur = computed(() => care.value?.uid ?? '');
</script>

<template>
  <div v-if="people.length" class="chips scroll" role="group" :aria-label="T.forWhom()" data-testid="care-switch">
    <button type="button" class="chip" :aria-pressed="!cur" @click="setCareMode(null)"><Icon v-if="!cur" name="check_circle" />{{ T.myself() }}</button>
    <button v-for="p in people" :key="p.patientUid" type="button" class="chip" :aria-pressed="cur === p.patientUid" @click="setCareMode({ uid: p.patientUid, name: p.patientName })">
      <Icon v-if="cur === p.patientUid" name="check_circle" />{{ p.patientName }}
    </button>
  </div>
</template>
