<script setup lang="ts">
// প্যাথলজি যোগ/বদল (অ্যাপের LabEditScreen)
import { ref } from 'vue';
import { Button, ErrorText, Switch, TextInput } from '@/ui/kit';
import type { AreaValue } from '@/ui/areaFilter';
import { useSession } from '@/lib/session';
import { call, errorText } from '@/lib/api';
import { isBangla, tr } from '@/lib/i18n';
import { normalizePhone } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { PAPERS_CHANGED, labDocs, missingDocs, sentForApproval, useAfterChange, type Lab } from './shared';
import AreaField from './AreaField.vue';
import DocPhotosField from './DocPhotosField.vue';
import StatusTag from './StatusTag.vue';
import StatusReason from './StatusReason.vue';
import TermsCheck from './TermsCheck.vue';
import LabHospitalPicker, { type LabHospital } from './LabHospitalPicker.vue';

const props = defineProps<{ existing: Lab | null }>();
const emit = defineEmits<{ done: [flash: string] }>();
const s = useSession();
const nav = useNav();
const after = useAfterChange();
const ex = props.existing;
const p = s.profile;
const facilityId = ref<string | null>(ex?.facilityId ?? null);
const name = ref(ex?.name ?? '');
const place = ref<AreaValue | null>(ex ? { district: ex.district, upazilaId: ex.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
const address = ref(ex?.address ?? '');
const license = ref(ex?.licenseNo ?? '');
const phone = ref(ex?.phone ?? p?.phone ?? '');
const hours = ref(ex?.hours ?? '');
const homeCollection = ref(ex?.homeCollection ?? false);
const docs = ref<Record<string, string>>({});
const agreed = ref(!!ex);
const pick = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);

async function save() {
  const specs = labDocs();
  const mobile = normalizePhone(phone.value);
  const e = !name.value.trim() ? tr('Write the centre name', 'সেন্টারের নাম লিখুন')
    : !place.value ? tr('Choose a district', 'জেলা বেছে নিন')
      : !address.value.trim() ? tr('Write the address', 'ঠিকানা লিখুন')
        : !license.value.trim() ? tr('Enter the DGHS licence number', 'স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর দিন')
          : !mobile ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
            : !ex && missingDocs(specs, docs.value) ? missingDocs(specs, docs.value)
              : !agreed.value ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
  err.value = e;
  if (e || !place.value) return;
  busy.value = true;
  try {
    const r = await call<{ pending?: boolean }>('lab_save', {
      facilityId: facilityId.value, name: name.value.trim(), district: place.value.district, upazilaId: place.value.upazilaId ?? null, address: address.value.trim(), licenseNo: license.value.trim(),
      phone: mobile, hours: hours.value.trim(), homeCollection: homeCollection.value, termsAccepted: true, docs: docs.value,
    });
    after();
    emit('done', r.pending ? sentForApproval('lab') : tr('Saved', 'সেভ হয়েছে'));
  } catch (x) {
    err.value = errorText(x);
    busy.value = false;
  }
}
function picked(h: LabHospital) {
  facilityId.value = h.id;
  name.value = isBangla() && h.nameBn ? h.nameBn : h.nameEn;
  place.value = { district: h.district, upazilaId: h.upazilaId };
  if (!address.value.trim()) address.value = h.address;
  pick.value = false;
}
</script>

<template>
  <div v-if="existing" class="row wrap gap-8">
    <StatusTag :status="existing.status" />
    <StatusReason :status="existing.status" :reason="existing.statusReason" />
  </div>
  <div class="col gap-6">
    <Button variant="outline" icon="local_hospital" class="work-self-start work-wrap-btn" @click="pick = true">
      {{ facilityId ? name : tr('Choose from the DGHS list', 'স্বাস্থ্য অধিদপ্তরের তালিকা থেকে বেছে নিন') }}
    </Button>
    <span class="t-body-s muted">{{ tr('Not in the list? Write the details below.', 'তালিকায় না পেলে নিচে নিজে লিখুন।') }}</span>
  </div>
  <TextInput :label="tr('Centre name', 'সেন্টারের নাম')" :model-value="name" maxlength="120" @update:model-value="(v) => { name = v; facilityId = null; }" />
  <AreaField :label="tr('Area', 'এলাকা')" :value="place" @change="(v) => { place = v; facilityId = null; }" />
  <TextInput v-model="address" :label="tr('Address', 'ঠিকানা')" maxlength="200" />
  <div class="work-two">
    <TextInput v-model="license" :label="tr('DGHS licence number', 'স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর')" maxlength="60" />
    <TextInput :label="tr('Mobile number', 'মোবাইল নম্বর')" inputmode="tel" :model-value="phone" @update:model-value="(v) => (phone = v.replace(/[^\d+০-৯]/g, '').slice(0, 20))" />
  </div>
  <TextInput v-model="hours" :label="tr('Hours (e.g. 8 AM – 10 PM)', 'সময় (যেমন: সকাল ৮টা – রাত ১০টা)')" maxlength="80" />
  <Switch v-model="homeCollection" :label="tr('Collect samples from home', 'বাসা থেকে নমুনা নিই')" />
  <DocPhotosField :specs="labDocs()" :photos="docs" :note="existing ? PAPERS_CHANGED() : null" @change="(d) => (docs = d)" />
  <TermsCheck v-model="agreed" />
  <ErrorText :text="err" />
  <div class="form-actions">
    <Button variant="text" :disabled="busy" @click="nav(existing ? '/work/lab/info' : '/work/lab')">{{ tr('Cancel', 'বাতিল') }}</Button>
    <Button :busy="busy" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
  </div>
  <LabHospitalPicker v-if="pick" :district="place?.district ?? null" @close="pick = false" @pick="picked" />
</template>
