<script setup lang="ts">
// "কোন চেম্বারে?" (২.৩৩.০): প্রতিটা চেম্বার স্পষ্ট চাপযোগ্য কার্ড — নাম · উপজেলা, পরের বসার দিন-সময়, ফি, ডানে "বেছে নিন ›";
// সামনে বসার দিন না থাকলে ধূসর আর চাপা যায় না।
import { computed } from 'vue';
import { Icon, cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { chamberLabel, chamberPlace, chamberState, feeShort, feeText, nextSlotText, type Provider } from './model';
import { nextOpenDays } from './doctors';

const props = defineProps<{ p: Provider; bookable: number[] }>();
const emit = defineEmits<{ pick: [i: number] }>();
const rows = computed(() =>
  props.bookable.map((i) => {
    const c = props.p.chambers[i];
    const st = chamberState(c);
    const off = st.kind === 'none' || !nextOpenDays(c, 1).length;
    return { i, c, st, off };
  }),
);
</script>

<template>
  <div class="col gap-8">
    <button v-for="r in rows" :key="r.i" type="button" class="appt-pick-card" :disabled="r.off" @click="emit('pick', r.i)">
      <span class="col grow appt-chpick-txt">
        <span class="t-title-s semi">{{ chamberLabel(r.c) }}</span>
        <span class="t-body-s muted">{{ chamberPlace(r.c) }}</span>
        <span :class="cx('t-body-m appt-st', r.off ? 'none' : r.st.kind)">
          {{ r.off ? tr('No upcoming days', 'সামনে বসার দিন নেই') : (r.st.kind === 'now' ? '' : tr('Next: ', 'পরের সময়: ')) + nextSlotText(r.st) }}
        </span>
        <span v-if="feeShort(r.c)" class="t-body-s muted">{{ feeText(r.c.noFee, r.c.fee) }}</span>
      </span>
      <span v-if="!r.off" class="appt-pick-go">{{ tr('Choose', 'বেছে নিন') }}<Icon name="chevron_right" /></span>
    </button>
  </div>
</template>
