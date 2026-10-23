<script setup lang="ts">
// এক ওষুধ — Enter: পরের ঘরে; নোটে (শেষ ঘর) Enter: নতুন ওষুধ
import { useId } from 'vue';
import { IconButton } from '@/ui/kit';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { medHowTo } from '../../health/rx';
import { DAYS, DOSES, timingLabel, type Drug } from './model';
import type { MedRow } from './write';
import MedField from './MedField.vue';
import DrugInput from './DrugInput.vue';

// free: হোমিওপ্যাথি/ইউনানি/আয়ুর্বেদ — নাম নিজে লেখা, ওষুধের তালিকা থেকে খোঁজা নেই (lib/practice.ts)
const props = defineProps<{ m: MedRow; n: number; last: boolean; free?: boolean }>();
const emit = defineEmits<{ change: [p: Partial<MedRow>]; remove: []; addNext: [] }>();
const doseList = 'dl' + useId().replace(/[^a-zA-Z0-9-]/g, '');
const daysList = 'dy' + useId().replace(/[^a-zA-Z0-9-]/g, '');
function onKey(e: KeyboardEvent) {
  if (e.key !== 'Enter' || e.defaultPrevented || e.ctrlKey || e.metaKey || e.shiftKey) return;
  const el = e.target as HTMLElement;
  if (el.tagName === 'TEXTAREA' || el.tagName === 'BUTTON') return;
  const cur = e.currentTarget as HTMLElement;
  const fields = [...cur.querySelectorAll<HTMLElement>('[data-f]')];
  const i = fields.indexOf(el);
  if (i < 0) return;
  e.preventDefault();
  if (i === fields.length - 1) {
    if (props.last) emit('addNext');
    else cur.nextElementSibling?.querySelector<HTMLElement>('[data-f="name"]')?.focus();
  } else fields[i + 1].focus();
}
function picked(d: Drug) {
  emit('change', { name: d.name.slice(0, 100), form: d.form.slice(0, 40), strength: d.generic.slice(0, 40), picked: true });
  // নাম বাছার পর সোজা খাওয়ার নিয়মে
  setTimeout(() => document.querySelector<HTMLInputElement>(`[data-med="${props.m.key}"] [data-f="dose"]`)?.focus(), 0);
}
const val = (e: Event) => (e.target as HTMLInputElement).value;
</script>

<template>
  <div :class="['rxd-med', free && 'rxd-free']" :data-med="m.key" @keydown="onKey">
    <span class="rxd-med-n num" aria-hidden="true">{{ num(n) }}.</span>
    <div class="rxd-c-name">
      <MedField v-if="free" cls="" :label="tr('Medicine name', 'ওষুধের নাম')">
        <input data-f="name" class="input rxd-in" :aria-label="tr('Medicine name', 'ওষুধের নাম') + ' ' + num(n)" :value="m.name" autocomplete="off" spellcheck="false" @input="emit('change', { name: val($event).slice(0, 200), picked: false })" />
      </MedField>
      <DrugInput v-else :value="m.name" :picked="m.picked" :label="tr('Medicine name', 'ওষুধের নাম') + ' ' + num(n)" @type="(v) => emit('change', { name: v, picked: false })" @pick="picked" />
    </div>
    <!-- free: ধরন, মাত্রা, খাবার নেই (সার্ভারের parseFreeMedicines: নাম, নিয়ম, দিন, নোট) -->
    <MedField v-if="!free" cls="rxd-c-form" :label="tr('Form', 'ধরন')">
      <input data-f="form" class="input rxd-in" :value="m.form" placeholder="Tablet" autocomplete="off" @input="emit('change', { form: val($event).slice(0, 40) })" />
    </MedField>
    <MedField v-if="!free" cls="rxd-c-str" :label="tr('Strength', 'মাত্রা')">
      <input data-f="strength" class="input rxd-in" :value="m.strength" placeholder="500 mg" autocomplete="off" @input="emit('change', { strength: val($event).slice(0, 40) })" />
    </MedField>
    <MedField cls="rxd-c-dose" :label="tr('How to take', 'খাওয়ার নিয়ম')">
      <input data-f="dose" class="input rxd-in num" :list="free ? undefined : doseList" :value="m.dose" autocomplete="off" @input="emit('change', { dose: val($event).slice(0, free ? 60 : 30) })" />
      <datalist v-if="!free" :id="doseList"><option v-for="d in DOSES" :key="d" :value="d" /></datalist>
    </MedField>
    <MedField v-if="!free" cls="rxd-c-time" :label="tr('Food', 'খাবার')">
      <select data-f="timing" class="select rxd-in" :value="m.timing" @change="emit('change', { timing: val($event) })">
        <option value="after">{{ timingLabel('after') }}</option>
        <option value="before">{{ timingLabel('before') }}</option>
        <option value="">—</option>
      </select>
    </MedField>
    <MedField cls="rxd-c-days" :label="tr('Days (0 = continue)', 'দিন (০ = চলবে)')">
      <input
        data-f="days"
        class="input rxd-in num"
        inputmode="numeric"
        :list="daysList"
        :value="m.days"
        :title="tr('0 = continue', '০ = চলবে')"
        autocomplete="off"
        @input="emit('change', { days: val($event).replace(/[^0-9০-৯]/g, '').slice(0, 3) })"
      />
      <datalist :id="daysList"><option v-for="d in DAYS" :key="d" :value="d" /></datalist>
    </MedField>
    <MedField cls="rxd-c-note" :label="tr('Note (optional)', 'নোট (ঐচ্ছিক)')">
      <input data-f="note" class="input rxd-in" :value="m.note" autocomplete="off" @input="emit('change', { note: val($event).slice(0, 120) })" />
    </MedField>
    <span class="rxd-c-x">
      <IconButton small icon="delete" :label="tr('Remove', 'সরান')" @click="emit('remove')" />
    </span>
    <span v-if="m.name.trim()" class="rxd-c-sum t-body-s muted">{{ medHowTo({ name: m.name, dose: m.dose, timing: m.timing, days: parseInt(toAsciiDigits(m.days), 10) || 0 }) }}</span>
  </div>
</template>
