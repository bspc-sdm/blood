<script setup lang="ts">
// হাসপাতালের ঘর: লিখতে থাকলে নিচে ওই জেলার হাসপাতালের নাম আসে, চাপলে বসে যায়
import { computed, ref } from 'vue';
import { Button, Icon, InfoText, TextInput } from '@/ui/kit';
import { call } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { areaLabel, bnNorm } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { useQ } from '@/lib/query';
import { hospitalLabel, type Hospital } from './hospital';
import AddHospitalDialog from './AddHospitalDialog.vue';

const props = defineProps<{ modelValue: string; district: string | null }>();
const emit = defineEmits<{ 'update:modelValue': [v: string] }>();
const focused = ref(false);
const picked = ref(false);
const adding = ref(false);
const added = ref<string | null>(null);
const s = useSession();
const hq = useQ<Hospital[]>(() => ({
  queryKey: ['hospitals', props.district],
  queryFn: () => call<{ items: Hospital[] }>('hospitals', { district: props.district }).then((o) => o.items ?? []),
  enabled: !!props.district,
  staleTime: 10 * 60_000,
}));
const q = computed(() => props.modelValue.trim());
const matches = computed(() => {
  if (q.value.length < 2 || picked.value) return [];
  const ql = q.value.toLowerCase();
  const qb = bnNorm(q.value);
  const starts = (h: Hospital) => h.nameEn.toLowerCase().startsWith(ql) || bnNorm(h.nameBn).startsWith(qb);
  return (hq.data ?? [])
    .filter((h) => h.nameEn.toLowerCase().includes(ql) || bnNorm(h.nameBn).includes(qb))
    .sort((a, b) => Number(starts(b)) - Number(starts(a)))
    .slice(0, 6);
});
function onInput(v: string) {
  emit('update:modelValue', v.slice(0, 120));
  picked.value = false;
}
function pick(h: Hospital) {
  emit('update:modelValue', hospitalLabel(h).slice(0, 120));
  picked.value = true;
  focused.value = false;
}
function onExisting(h: Hospital) {
  adding.value = false;
  emit('update:modelValue', hospitalLabel(h).slice(0, 120));
  picked.value = true;
}
function onSent(m: string) {
  adding.value = false;
  added.value = m;
}
const onBlur = () => setTimeout(() => (focused.value = false), 150);
const askAdd = () => s.requireLogin(tr('Log in to request a hospital', 'হাসপাতাল যোগের অনুরোধ করতে লগইন করুন'), () => (adding.value = true));
</script>

<template>
  <div class="col gap-4 blood-hosp">
    <div class="blood-hosp-input">
      <Icon name="local_hospital" class="blood-hosp-ic" />
      <TextInput
        :label="tr('Hospital', 'হাসপাতাল')"
        :model-value="modelValue"
        maxlength="120"
        autocomplete="off"
        @update:model-value="onInput"
        @focus="focused = true"
        @blur="onBlur"
      />
    </div>
    <div v-if="focused && matches.length" class="blood-suggest" role="listbox">
      <button v-for="h in matches" :key="h.id" type="button" role="option" :aria-selected="false" @mousedown.prevent @click="pick(h)">
        <span class="t-body-m semi">{{ hospitalLabel(h) }}</span>
        <span v-if="areaLabel(h.district, h.upazilaId, h.unionId)" class="t-body-s muted">{{ areaLabel(h.district, h.upazilaId, h.unionId) }}</span>
      </button>
    </div>
    <div class="row gap-4">
      <span class="t-body-s muted">{{ tr('Not in the list?', 'তালিকায় নেই?') }}</span>
      <Button variant="text" small @click="askAdd">{{ tr('Request to add', 'যোগ করার অনুরোধ') }}</Button>
    </div>
    <InfoText :text="added" />
    <AddHospitalDialog v-if="adding" :initial-name="q" :district="district" @existing="onExisting" @sent="onSent" @dismiss="adding = false" />
  </div>
</template>
