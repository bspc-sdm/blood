<script setup lang="ts">
// তারিখ আর সময়: অ্যাপের মতো দুটো বাটন, চাপলে ব্রাউজারের তারিখ/সময় বাছাই
import { ref } from 'vue';
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { DAY_MS } from '@/lib/rules';
import { dateInputValue, formatDate, formatTimeHM, fromDateInput, todayUtcMidnight } from '@/lib/format';

defineProps<{ date: number; hour: number | null; minute: number | null }>();
const emit = defineEmits<{ date: [d: number]; time: [h: number, m: number] }>();
const dRef = ref<HTMLInputElement | null>(null);
const tRef = ref<HTMLInputElement | null>(null);
const open = (el: HTMLInputElement | null) => {
  if (!el) return;
  try {
    el.showPicker();
  } catch {
    el.focus();
    el.click();
  }
};
const today = todayUtcMidnight();
function onDate(e: Event) {
  const v = fromDateInput((e.target as HTMLInputElement).value);
  if (v != null) emit('date', v);
}
function onTime(e: Event) {
  const m = /^(\d{2}):(\d{2})/.exec((e.target as HTMLInputElement).value);
  if (m) emit('time', parseInt(m[1], 10), parseInt(m[2], 10));
}
</script>

<template>
  <div class="blood-dt">
    <div class="blood-pick">
      <button type="button" class="btn outline has-icon blood-pickbtn" @click="open(dRef)">
        <Icon name="calendar_month" />
        <span class="ellipsis">{{ formatDate(date) }}</span>
      </button>
      <input
        ref="dRef"
        type="date"
        tabindex="-1"
        :aria-label="tr('Date', 'তারিখ')"
        :min="dateInputValue(today)"
        :max="dateInputValue(today + 30 * DAY_MS)"
        :value="dateInputValue(date)"
        @change="onDate"
        @input="onDate"
      />
    </div>
    <div class="blood-pick">
      <button type="button" class="btn outline has-icon blood-pickbtn" @click="open(tRef)">
        <Icon name="schedule" />
        <span class="ellipsis">{{ hour != null && minute != null ? formatTimeHM(hour, minute) : tr('Time', 'সময়') }}</span>
      </button>
      <input
        ref="tRef"
        type="time"
        tabindex="-1"
        :aria-label="tr('Time', 'সময়')"
        :value="hour != null && minute != null ? `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}` : ''"
        @change="onTime"
        @input="onTime"
      />
    </div>
  </div>
</template>
