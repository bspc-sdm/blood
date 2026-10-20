<script setup lang="ts">
// হাসপাতাল যোগের অনুরোধ: লেখার সময়ই মিলে যাওয়া হাসপাতাল দেখায়, যাতে একই হাসপাতাল দুবার না আসে
import { computed, ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, Icon, TextInput } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import type { AreaValue } from '@/ui/areaFilter';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { HOSPITAL_TYPES, hospitalLabel, hospitalTypeLabel, similarHospitals, type Hospital } from './model';

const props = defineProps<{ initialName: string; initialDistrict: string | null; initialUpazilaId: number | null }>();
const emit = defineEmits<{ existing: [h: Hospital]; sent: [msg: string]; close: [] }>();
const latin = /[A-Za-z]/.test(props.initialName);
const nameEn = ref(latin ? props.initialName : '');
const nameBn = ref(latin ? '' : props.initialName);
const type = ref('private_hospital');
const area = ref<{ district: string | null; upazilaId: number | null; unionId: number | null }>({ district: props.initialDistrict, upazilaId: props.initialUpazilaId, unionId: null });
const address = ref('');
const phone = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const existing = ref<Hospital | null>(null);
const picking = ref(false);
const hq = useCall<{ items: Hospital[] }>('hospitals', () => ({ district: area.value.district }), () => ({ enabled: !!area.value.district }));
const suggestions = computed(() => {
  const inArea = (hq.data?.items ?? []).filter((h) => area.value.upazilaId == null || h.upazilaId === area.value.upazilaId || h.upazilaId == null);
  return [...new Set([...similarHospitals(nameEn.value, inArea, 4), ...(nameBn.value.trim().length >= 3 ? inArea.filter((h) => h.nameBn.includes(nameBn.value.trim())).slice(0, 3) : [])])];
});

async function send() {
  if (!area.value.district) {
    err.value = tr('Choose a district', 'জেলা বেছে নিন');
    return;
  }
  if (!/[A-Za-z]/.test(nameEn.value)) {
    err.value = tr('Enter the name in English', 'ইংরেজিতে নাম লিখুন');
    return;
  }
  busy.value = true;
  err.value = null;
  try {
    const title = nameEn.value.trim().replace(/\s+/g, ' ').replace(/\b([a-z])/g, (m) => m.toUpperCase());
    const r = await call<{ ok?: boolean; merged?: boolean; duplicateOf?: Hospital }>('hospital_request', {
      nameEn: title, nameBn: nameBn.value.trim(), type: type.value, district: area.value.district, upazilaId: area.value.upazilaId, unionId: area.value.unionId, address: address.value.trim(), phone: phone.value,
    });
    if (r.duplicateOf) existing.value = r.duplicateOf;
    else if (r.merged) emit('sent', tr('Someone already asked to add this hospital — your request was added to theirs.', 'এই হাসপাতালের অনুরোধ আগেই এসেছে — আপনারটাও তার সাথে যোগ হলো।'));
    else emit('sent', tr('Request sent. You can choose it after an admin approves.', 'অনুরোধ পাঠানো হয়েছে। অ্যাডমিন অনুমোদন দিলে বেছে নিতে পারবেন।'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function areaPicked(v: AreaValue) {
  area.value = { district: v.district, upazilaId: v.upazilaId, unionId: v.unionId ?? null };
  picking.value = false;
}
</script>

<template>
  <Dialog :title="tr('Add a hospital', 'হাসপাতাল যোগের অনুরোধ')" @close="() => !busy && emit('close')">
    <p class="t-body-s muted">{{ tr('An admin checks it before it appears in the list.', 'অ্যাডমিন যাচাই করার পর তালিকায় আসবে।') }}</p>
    <button type="button" class="area-btn" @click="picking = true">
      <Icon name="location_on" />
      <span>{{ area.district ? areaLabel(area.district, area.upazilaId, area.unionId) : tr('Choose a district', 'জেলা বেছে নিন') }}</span>
      <Icon name="expand_more_round" />
    </button>
    <TextInput :label="tr('Name in English', 'ইংরেজিতে নাম')" :model-value="nameEn" @update:model-value="(v) => (nameEn = v.slice(0, 120))" />
    <TextInput :label="tr('Name in Bangla (optional)', 'বাংলায় নাম (ঐচ্ছিক)')" :model-value="nameBn" @update:model-value="(v) => (nameBn = v.slice(0, 120))" />
    <div v-if="suggestions.length" class="card tonal col gap-4">
      <span class="t-label-l">{{ tr('Is it one of these? Tap to choose.', 'এর কোনোটা কি? থাকলে ট্যাপ করুন।') }}</span>
      <button v-for="h in suggestions" :key="h.id" type="button" class="appt-link-row" @click="emit('existing', h)">• {{ hospitalLabel(h) }}</button>
    </div>
    <div class="col gap-6">
      <span class="t-label-l">{{ tr('Type', 'ধরন') }}</span>
      <ChoiceChips v-model="type" :options="HOSPITAL_TYPES.map((t) => ({ value: t, label: hospitalTypeLabel(t) }))" />
    </div>
    <TextInput :label="tr('Address (optional)', 'ঠিকানা (ঐচ্ছিক)')" :model-value="address" @update:model-value="(v) => (address = v.slice(0, 200))" />
    <TextInput :label="tr('Phone (optional)', 'ফোন (ঐচ্ছিক)')" inputmode="tel" :model-value="phone" @update:model-value="(v) => (phone = v.replace(/[^0-9+]/g, '').slice(0, 15))" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
  <AreaPicker
    v-if="picking"
    with-unions
    :value="area.district ? { district: area.district, upazilaId: area.upazilaId, unionId: area.unionId } : null"
    @picked="areaPicked"
    @close="picking = false"
  />
  <Dialog v-if="existing" size="small" :title="tr('Already in the list', 'তালিকায় আগে থেকেই আছে')" @close="existing = null">
    <p class="t-body-m">{{ hospitalLabel(existing) }}</p>
    <p class="t-body-s muted">{{ areaLabel(existing.district, existing.upazilaId) }}</p>
    <template #actions>
      <Button variant="text" @click="existing = null">{{ tr('Back', 'ফিরে যান') }}</Button>
      <Button @click="emit('existing', existing!)">{{ tr('Choose this', 'এটাই বেছে নিন') }}</Button>
    </template>
  </Dialog>
</template>
