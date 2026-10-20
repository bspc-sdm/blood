<script setup lang="ts">
// বার আর শুরু–শেষের ভাগ (চেম্বার আর বাসায় সেবা): প্রতিটা ভাগে বার, সময়, আর "অন্য বারে আলাদা সময়"
import { Button, ChoiceChips, IconButton, TextInput } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { MAX_CHAMBER_TIMES, WEEK_ORDER, dayShort, type ChamberTime } from '../model';
import { newSlot } from './chamber';

const props = defineProps<{ slots: ChamberTime[] }>();
const emit = defineEmits<{ change: [l: ChamberTime[]] }>();
const set = (i: number, t: ChamberTime) => emit('change', props.slots.map((x, j) => (j === i ? t : x)));
</script>

<template>
  <div class="col gap-12">
    <div v-for="(s, i) in slots" :key="i" class="col gap-6 appt-slot">
      <div class="row">
        <span class="t-label-l grow">{{ slots.length > 1 ? tr(`Time ${i + 1}: days`, `সময় ${num(i + 1)}: কোন কোন বার`) : tr('Days', 'কোন কোন বার') }}</span>
        <IconButton v-if="slots.length > 1" small icon="close" :label="tr('Remove this time', 'এই সময় সরান')" @click="emit('change', slots.filter((_, j) => j !== i))" />
      </div>
      <ChoiceChips multi :options="WEEK_ORDER.map((d) => ({ value: d, label: dayShort(d) }))" :model-value="s.days" @update:model-value="(v: number[]) => set(i, { ...s, days: v })" />
      <div class="row appt-times">
        <TextInput type="time" :label="tr('Start time', 'শুরুর সময়')" :model-value="s.start" @update:model-value="(v) => set(i, { ...s, start: v })" />
        <span class="appt-dash">–</span>
        <TextInput type="time" :label="tr('End time', 'শেষের সময়')" :model-value="s.end" @update:model-value="(v) => set(i, { ...s, end: v })" />
      </div>
    </div>
    <Button v-if="slots.length < MAX_CHAMBER_TIMES" variant="text" icon="add" :style="{ alignSelf: 'flex-start' }" @click="emit('change', [...slots, newSlot()])">
      {{ tr('Different time on other days', 'অন্য বারে আলাদা সময়') }}
    </Button>
  </div>
</template>
