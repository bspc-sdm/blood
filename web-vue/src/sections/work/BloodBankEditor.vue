<script setup lang="ts">
// ব্লাড ব্যাংক যোগ/বদল
import { ref } from 'vue';
import { Button, ChoiceChips, ConfirmDialog, ErrorText, Field, Switch, TextInput } from '@/ui/kit';
import type { AreaValue } from '@/ui/areaFilter';
import { useSession } from '@/lib/session';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { BB_KINDS, PAPERS_CHANGED, bbKindLabel, bloodBankDocs, isContactPhone, missingDocs, phoneInput, sentForApproval, useAfterChange, useCatalog, type BloodBank } from './shared';
import AreaField from './AreaField.vue';
import DocPhotosField from './DocPhotosField.vue';
import StatusTag from './StatusTag.vue';
import StatusReason from './StatusReason.vue';
import TermsCheck from './TermsCheck.vue';
import TimeRange from './TimeRange.vue';
import WeekdayChips from './WeekdayChips.vue';

const props = defineProps<{ existing: BloodBank | null }>();
const emit = defineEmits<{ done: [flash: string] }>();
const s = useSession();
const nav = useNav();
const cat = useCatalog('bloodBankService');
const after = useAfterChange();
const ex = props.existing;
const p = s.profile;
const name = ref(ex?.name ?? '');
const kind = ref(ex?.kind ?? 'govt');
const phone = ref(ex?.phone ?? p?.phone ?? '');
const licenseNo = ref(ex?.licenseNo ?? '');
const place = ref<AreaValue | null>(ex ? { district: ex.district, upazilaId: ex.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
const address = ref(ex?.address ?? '');
const open24 = ref(ex?.open24 ?? true);
const openAt = ref(ex?.openAt || '08:00');
const closeAt = ref(ex?.closeAt || '20:00');
const closedDays = ref<number[]>(ex?.closedDays ?? []);
const services = ref<string[]>(ex?.services ?? ['whole_blood', 'screening']);
const docs = ref<Record<string, string>>({});
const agreed = ref(!!ex);
const busy = ref(false);
const err = ref<string | null>(null);
const del = ref(false);
const delErr = ref<string | null>(null);

async function save() {
  const specs = bloodBankDocs(kind.value);
  const e = !name.value.trim() ? tr('Enter the blood bank name', 'ব্লাড ব্যাংকের নাম লিখুন')
    : !isContactPhone(phone.value) ? tr('Enter a valid phone number', 'সঠিক ফোন নম্বর দিন')
      : kind.value === 'private' && !licenseNo.value.trim() ? tr('Enter the blood bank licence number', 'ব্লাড ব্যাংকের লাইসেন্স নম্বর দিন')
        : !place.value ? tr('Choose a district', 'জেলা বেছে নিন')
          : !address.value.trim() ? tr('Write the address', 'ঠিকানা লিখুন')
            : !open24.value && (!openAt.value || !closeAt.value || openAt.value === closeAt.value) ? tr('Set the opening hours', 'খোলার সময় দিন')
              : !services.value.length ? tr('Choose the services', 'কী কী সেবা দেন বেছে নিন')
                : !ex && missingDocs(specs, docs.value) ? missingDocs(specs, docs.value)
                  : !agreed.value ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
  err.value = e;
  if (e || !place.value) return;
  busy.value = true;
  try {
    const r = await call<{ pending?: boolean }>('bloodbank_save', {
      id: ex?.id ?? null, name: name.value.trim(), kind: kind.value, phone: phone.value, district: place.value.district, upazilaId: place.value.upazilaId ?? null, address: address.value.trim(),
      licenseNo: licenseNo.value.trim(), open24: open24.value, openAt: open24.value ? '' : openAt.value, closeAt: open24.value ? '' : closeAt.value, services: services.value,
      closedDays: [...closedDays.value].sort((a, b) => a - b), termsAccepted: true, docs: docs.value,
    });
    after();
    emit('done', r.pending ? sentForApproval('bloodbank') : tr('Saved', 'সেভ হয়েছে'));
  } catch (x) {
    err.value = errorText(x);
    busy.value = false;
  }
}
async function remove() {
  if (!ex) return;
  busy.value = true;
  delErr.value = null;
  try {
    await call('bloodbank_delete', { id: ex.id });
    after();
    emit('done', tr('Removed', 'মুছে ফেলা হয়েছে'));
  } catch (x) {
    delErr.value = errorText(x);
    busy.value = false;
  }
}
</script>

<template>
  <div v-if="existing" class="row wrap gap-8">
    <StatusTag :status="existing.status" />
    <StatusReason :status="existing.status" :reason="existing.statusReason" />
  </div>
  <TextInput v-model="name" :label="tr('Blood bank name', 'ব্লাড ব্যাংকের নাম')" maxlength="100" autocapitalize="words" />
  <Field :label="tr('Type', 'ধরন')">
    <ChoiceChips v-model="kind" :options="BB_KINDS.map((k) => ({ value: k, label: bbKindLabel(k) }))" />
  </Field>
  <div class="work-two">
    <TextInput :label="tr('Phone for calls', 'যে নম্বরে ফোন আসবে')" :hint="tr('Mobile, landline or hotline', 'মোবাইল, ল্যান্ডলাইন বা হটলাইন')" inputmode="tel" :model-value="phone" @update:model-value="(v) => (phone = phoneInput(v))" />
    <TextInput v-if="kind === 'private'" v-model="licenseNo" :label="tr('Licence number', 'লাইসেন্স নম্বর')" maxlength="60" />
  </div>
  <AreaField :label="tr('Area', 'এলাকা')" :value="place" @change="(v) => (place = v)" />
  <TextInput v-model="address" :label="tr('Address', 'ঠিকানা')" maxlength="200" />
  <Switch v-model="open24" :label="tr('Open 24 hours', '২৪ ঘণ্টা খোলা')" />
  <TimeRange v-if="!open24" :open-at="openAt" :close-at="closeAt" @change="(o, c) => { openAt = o; closeAt = c; }" />
  <WeekdayChips :value="closedDays" @change="(v) => (closedDays = v)" />
  <Field :label="tr('Services', 'কী কী সেবা')">
    <ChoiceChips v-model="services" multi :options="cat.options.value" />
  </Field>
  <DocPhotosField :specs="bloodBankDocs(kind)" :photos="docs" :note="existing ? PAPERS_CHANGED() : null" @change="(d) => (docs = d)" />
  <TermsCheck v-model="agreed" />
  <ErrorText :text="err" />
  <div class="form-actions">
    <Button v-if="existing && existing.status !== 'suspended'" variant="danger-text" class="work-del" :disabled="busy" @click="del = true">{{ tr('Remove this blood bank', 'এই ব্লাড ব্যাংক সরিয়ে দিন') }}</Button>
    <Button variant="text" :disabled="busy" @click="nav('/work/bloodbank')">{{ tr('Cancel', 'বাতিল') }}</Button>
    <Button :busy="busy" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
  </div>
  <ConfirmDialog
    v-if="del && existing"
    :title="tr('Remove this blood bank?', 'এই ব্লাড ব্যাংক সরিয়ে দেবেন?')"
    :text="existing.name"
    :confirm="tr('Remove', 'মুছে ফেলুন')"
    danger
    :busy="busy"
    :error="delErr"
    @confirm="remove"
    @close="del = false"
  />
</template>
