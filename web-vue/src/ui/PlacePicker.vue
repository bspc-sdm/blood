<script setup lang="ts">
// PlacePicker — নিজের এলাকা (GPS বা জেলা), রক্তের রিকোয়েস্ট/কাছের জিনিসের জন্য (অ্যাপের LocationPicker)
import { computed, ref } from 'vue';
import { tr, isBangla } from '@/lib/i18n';
import { bnNorm, currentPosition, districtAt, districtsSorted } from '@/lib/geo';
import type { Place } from '@/lib/session';
import Dialog from './Dialog.vue';
import Button from './Button.vue';
import ErrorText from './ErrorText.vue';
import ListRow from './ListRow.vue';

const props = defineProps<{ title?: string; busy?: boolean; error?: string | null }>();
const emit = defineEmits<{ picked: [p: Place]; close: [] }>();
const q = ref('');
const gps = ref(false);
const err = ref<string | null>(null);
const list = computed(() => {
  const t = q.value.trim().toLowerCase();
  const b = bnNorm(q.value.trim());
  return districtsSorted().filter((d) => !t || bnNorm(d.bn).includes(b) || d.en.toLowerCase().includes(t));
});

async function useGps() {
  gps.value = true;
  err.value = null;
  try {
    const { lat, lng } = await currentPosition();
    const d = await districtAt(lat, lng);
    emit('picked', { lat, lng, placeName: d.bn, source: 'gps' });
  } catch {
    err.value = tr("Couldn't get your location. Allow location in the browser or pick a district.", 'লোকেশন পাওয়া যায়নি। ব্রাউজারে লোকেশনের অনুমতি দিন বা জেলা বেছে নিন।');
  } finally {
    gps.value = false;
  }
}
void props;
</script>

<template>
  <Dialog :title="title ?? tr('Your area', 'আপনার এলাকা')" @close="emit('close')">
    <Button icon="location_on" block :busy="gps || busy" @click="useGps">{{ tr('Use my current location', 'বর্তমান লোকেশন ব্যবহার করুন') }}</Button>
    <p class="t-body-s muted">{{ tr('Only your approximate area (~1 km) is saved.', 'শুধু আনুমানিক এলাকা (≈১ কিমি) রাখা হয়।') }}</p>
    <ErrorText :text="err ?? error" />
    <input v-model="q" class="input" :placeholder="tr('Search district', 'জেলা খুঁজুন')" :aria-label="tr('Search district', 'জেলা খুঁজুন')" />
    <div class="col gap-2" :style="{ maxHeight: '45dvh', overflowY: 'auto', margin: '0 -8px' }">
      <ListRow
        v-for="d in list"
        :key="d.en"
        :title="isBangla() ? d.bn : d.en"
        :sub="isBangla() ? d.en : d.bn"
        @click="emit('picked', { lat: d.lat, lng: d.lng, placeName: d.bn, source: 'district' })"
      >
        <template #trailing><span /></template>
      </ListRow>
    </div>
  </Dialog>
</template>
