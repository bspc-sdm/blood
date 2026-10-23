<script setup lang="ts">
// নামের নিচে চেম্বার বাছাই (২.৩৩.০, পারভেজ): প্রতিটা চেম্বার ছোট চাপযোগ্য কার্ড — ছোট নাম · উপজেলা, আজকের অবস্থা
// ("আজ বসবেন ৪টা–৯টা" / "আজ বন্ধ · আগামীকাল ১০টা"), ফি। অ্যাপয়েন্টমেন্ট নেন এমন চেম্বারে চাপলে সরাসরি বুকিং;
// না নিলে সিরিয়ালের ফোন (থাকলে)। নিচের বিস্তারিত ভাগগুলো তথ্যের জন্য থাকে।
import { computed } from 'vue';
import { Icon, cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { chamberLabel, chamberPlace, chamberShort, chamberState, feeShort, type Provider } from './model';

const props = defineProps<{ p: Provider; bookable: number[]; preview?: boolean }>();
const emit = defineEmits<{ book: [i: number] }>();
const rows = computed(() =>
  props.p.chambers.map((c, i) => {
    const st = chamberState(c);
    const can = props.bookable.includes(i) && st.kind !== 'none';
    const tel = !props.bookable.includes(i) && c.serialPhone ? c.serialPhone : null;
    const extra = props.bookable.includes(i) ? null : tel ? tr('Serial by phone', 'সিরিয়াল ফোনে') : tr('No appointment in app', 'অ্যাপে অ্যাপয়েন্টমেন্ট নেই');
    return { c, i, st, can, tel, line: [chamberPlace(c), feeShort(c), extra].filter(Boolean).join(' · ') };
  }),
);
</script>

<template>
  <div v-if="p.chambers.length" class="col gap-6 appt-chpick">
    <span class="t-label-l muted">{{ bookable.length ? tr('Choose a chamber to book', 'চেম্বার বেছে অ্যাপয়েন্টমেন্ট নিন') : tr('Chambers', 'চেম্বার') }}</span>
    <template v-for="r in rows" :key="r.i">
      <component
        :is="r.can ? 'button' : r.tel && !preview ? 'a' : 'div'"
        :class="cx('appt-chpick-card', !r.can && !(r.tel && !preview) && 'static')"
        v-bind="r.can ? { type: 'button', 'aria-label': tr('Appointment at ', 'অ্যাপয়েন্টমেন্ট: ') + chamberLabel(r.c) } : r.tel && !preview ? { href: `tel:${r.tel}` } : {}"
        @click="r.can && emit('book', r.i)"
      >
        <span class="appt-chpick-ic"><Icon :name="r.c.mode === 'online' ? 'language' : 'local_hospital'" /></span>
        <span class="col grow appt-chpick-txt">
          <span class="t-title-s semi appt-chpick-name">{{ chamberShort(r.c) }}</span>
          <span v-if="r.st.text" :class="cx('t-body-s appt-st', r.st.kind)">{{ r.st.text }}</span>
          <span class="t-body-s muted">{{ r.line }}</span>
        </span>
        <span v-if="r.can" class="appt-chpick-go"><Icon name="calendar_month" /></span>
        <span v-else-if="r.tel" class="appt-chpick-go"><Icon name="call" /></span>
      </component>
    </template>
  </div>
</template>
