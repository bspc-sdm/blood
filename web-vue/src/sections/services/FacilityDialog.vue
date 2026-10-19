<script setup lang="ts">
// "কোথায় যাবেন" (২.৩১.০): হাসপাতাল/ক্লিনিকের তালিকা থেকে বাছা, নয়তো "অন্য জায়গা লিখুন"।
// তালিকা সার্ভারের amb_facilities (DGHS Facility Registry, ডায়াগনস্টিক বাদ; lib/ambplace.js): রোগীর জেলারটা আগে
// (জায়গা থাকলে কাছেরটা আগে), তারপর সারা দেশের বড় হাসপাতাল; খোঁজা সারা দেশে, বাংলা/ইংরেজিতে ("য়" একরকম করে)।
import { computed, ref, watch } from 'vue';
import { Button, Dialog, ErrorText, ListRow, Loading, TextInput } from '@/ui/kit';
import { call } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { bnNorm } from '@/lib/geo';
import type { Point } from './place';
import { nameOf, subOf, toFacility, type Facility, type FacilityRow } from './facility';

const props = defineProps<{ title: string; district: string; near?: Point | null; otherLabel?: string; onOther?: (text: string) => void }>();
const emit = defineEmits<{ pick: [f: Facility]; close: [] }>();
const q = ref('');
const other = ref<string | null>(null);
const base = useCall<{ items: FacilityRow[]; top: FacilityRow[] }>('amb_facilities', () => ({ district: props.district, ...(props.near ? { lat: props.near.lat, lng: props.near.lng } : {}) }), { staleTime: 10 * 60_000 });
// খোঁজা: আগে জেলার তালিকায় সাথে সাথে, তারপর সার্ভারে সারা দেশে (৩০০ মি.সে. থেমে)
const remote = ref<FacilityRow[] | null>(null);
const t = computed(() => q.value.trim());
watch(
  () => [t.value, props.district] as const,
  ([tv, district], _o, onCleanup) => {
    remote.value = null;
    if (tv.length < 2) return;
    let live = true;
    const h = setTimeout(() => {
      call<{ items: FacilityRow[] }>('amb_facilities', { district, q: tv }).then((r) => live && (remote.value = r.items), () => {});
    }, 300);
    onCleanup(() => {
      live = false;
      clearTimeout(h);
    });
  },
  { immediate: true },
);
const shown = computed(() => {
  const items = base.data?.items ?? [];
  const top = base.data?.top ?? [];
  if (!t.value) {
    const ids = new Set(items.map((r) => r[0]));
    return { local: items.slice(0, 300), top: top.filter((r) => !ids.has(r[0])) };
  }
  const words = bnNorm(t.value).split(/\s+/).filter(Boolean);
  const hit = (r: FacilityRow) => {
    const hay = `${bnNorm(r[2])} ${r[1].toLowerCase()}`;
    return words.every((w) => hay.includes(w));
  };
  const local = [...items, ...top].filter(hit);
  const seen = new Set(local.map((r) => r[0]));
  return { local: [...local, ...(remote.value ?? []).filter((r) => !seen.has(r[0]))].slice(0, 80), top: [] as FacilityRow[] };
});
const otherOk = computed(() => (other.value ?? '').trim().length >= 3);
</script>

<template>
  <Dialog v-if="other != null" :title="otherLabel ?? tr('Another place', 'অন্য জায়গা')" size="small" @close="emit('close')">
    <TextInput v-model="other" :label="tr('Hospital, clinic or place name', 'হাসপাতাল, ক্লিনিক বা জায়গার নাম')" maxlength="80" autofocus data-testid="fac-other-input" />
    <template #actions>
      <Button variant="text" @click="other = null">{{ tr('Back', 'ফিরে যান') }}</Button>
      <Button :disabled="!otherOk" data-testid="fac-other-ok" @click="onOther?.(other.trim())">{{ tr('OK', 'ঠিক আছে') }}</Button>
    </template>
  </Dialog>
  <Dialog v-else :title="title" @close="emit('close')">
    <TextInput v-model="q" :label="tr('Search hospital or clinic', 'হাসপাতাল বা ক্লিনিক খুঁজুন')" data-testid="fac-search" />
    <Button v-if="onOther" variant="outline" icon="edit" data-testid="fac-other" @click="other = t">{{ otherLabel ?? tr('Write another place', 'অন্য জায়গা লিখুন') }}</Button>
    <Loading v-if="base.isLoading" inline />
    <ErrorText :text="base.error ? base.error.message : null" />
    <div class="col gap-2 fac-list" data-testid="fac-list">
      <span v-if="shown.local.length" class="t-label-l muted fac-head">{{ t ? tr('Results', 'খোঁজার ফল') : near ? tr('In your district — nearest first', 'আপনার জেলায় — কাছেরটা আগে') : tr('In your district', 'আপনার জেলায়') }}</span>
      <ListRow v-for="r in shown.local" :key="r[0]" icon="local_hospital" :title="nameOf(r)" :sub="subOf(r)" @click="emit('pick', toFacility(r))"><template #trailing><span /></template></ListRow>
      <span v-if="shown.top.length" class="t-label-l muted fac-head">{{ tr('Major hospitals in the country', 'দেশের বড় হাসপাতাল') }}</span>
      <ListRow v-for="r in shown.top" :key="r[0]" icon="local_hospital" :title="nameOf(r)" :sub="subOf(r)" @click="emit('pick', toFacility(r))"><template #trailing><span /></template></ListRow>
      <span v-if="t && !shown.local.length && remote" class="t-body-s muted">{{ tr('Not found — write it with "Write another place".', 'পাওয়া যায়নি — "অন্য জায়গা লিখুন" দিয়ে লিখুন।') }}</span>
    </div>
  </Dialog>
</template>
