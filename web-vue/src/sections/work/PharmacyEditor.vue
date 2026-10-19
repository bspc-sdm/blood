<script setup lang="ts">
// ফার্মেসি যোগ/বদল (অ্যাপের ShopEditScreen)
import { ref } from 'vue';
import { Button, ErrorText, InfoText, Switch, TextInput } from '@/ui/kit';
import type { AreaValue } from '@/ui/areaFilter';
import { useSession } from '@/lib/session';
import { call, errorText } from '@/lib/api';
import { toAsciiDigits, tr } from '@/lib/i18n';
import { currentPosition } from '@/lib/geo';
import { normalizePhone } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { PAPERS_CHANGED, missingDocs, pharmacyDocs, sentForApproval, useAfterChange, type Pharmacy } from './shared';
import AreaField from './AreaField.vue';
import DocPhotosField from './DocPhotosField.vue';
import StatusTag from './StatusTag.vue';
import StatusReason from './StatusReason.vue';
import TermsCheck from './TermsCheck.vue';
import TimeRange from './TimeRange.vue';
import WeekdayChips from './WeekdayChips.vue';

/** টাকার ঘর: খালি বা ভুল হলে ০ (সর্বোচ্চ ১,০০,০০০) */
const takaOf = (v: string) => Math.min(100000, parseInt(toAsciiDigits(v) || '0', 10) || 0);

const props = defineProps<{ existing: Pharmacy | null }>();
const emit = defineEmits<{ done: [flash: string] }>();
const s = useSession();
const nav = useNav();
const after = useAfterChange();
const ex = props.existing;
const p = s.profile;
const name = ref(ex?.name ?? '');
const licenseNo = ref(ex?.licenseNo ?? '');
const phone = ref(ex?.phone ?? p?.phone ?? '');
const place = ref<AreaValue | null>(ex ? { district: ex.district, upazilaId: ex.upazilaId } : p?.placeName ? { district: p.placeName, upazilaId: null } : null);
const address = ref(ex?.address ?? '');
const loc = ref<{ lat: number; lng: number } | null>(null);
const gps = ref(false);
const gpsErr = ref<string | null>(null);
const open24 = ref(ex?.open24 ?? false);
const openAt = ref(ex?.openAt || '09:00');
const closeAt = ref(ex?.closeAt || '23:00');
const closedDays = ref<number[]>(ex?.closedDays ?? []);
const delivery = ref(ex?.delivery ?? true);
const deliveryNote = ref(ex?.deliveryNote ?? '');
const discount = ref(String(ex?.discount ?? 0));
// ছাড়ের শর্ত (২.৩২.০, ঐচ্ছিক): ফার্মেসির তালিকার কার্ডে দেখায়
const discountMin = ref(ex?.discountMin ? String(ex.discountMin) : '');
const freeOver = ref(ex?.freeDeliveryOver ? String(ex.freeDeliveryOver) : '');
const discountNote = ref(ex?.discountNote ?? '');
const docs = ref<Record<string, string>>({});
const agreed = ref(!!ex);
const busy = ref(false);
const err = ref<string | null>(null);
const hadLocation = !!ex?.hasLocation;

async function useGps() {
  gps.value = true;
  gpsErr.value = null;
  try {
    loc.value = await currentPosition();
  } catch {
    gpsErr.value = tr("Couldn't get your location. Allow location in the browser or pick a district.", 'লোকেশন পাওয়া যায়নি। ব্রাউজারে লোকেশনের অনুমতি দিন বা জেলা বেছে নিন।');
  } finally {
    gps.value = false;
  }
}

async function save() {
  const specs = pharmacyDocs();
  const mobile = normalizePhone(phone.value);
  const disc = parseInt(toAsciiDigits(discount.value) || '0', 10) || 0;
  const e = !name.value.trim() ? tr('Enter the pharmacy name', 'ফার্মেসির নাম লিখুন')
    : !licenseNo.value.trim() ? tr('Enter the drug licence number', 'ড্রাগ লাইসেন্স নম্বর দিন')
      : !mobile ? tr('Enter a valid mobile number', 'সঠিক মোবাইল নম্বর দিন')
        : !place.value ? tr('Choose a district', 'জেলা বেছে নিন')
          : !address.value.trim() ? tr('Write the shop address', 'দোকানের ঠিকানা লিখুন')
            : !loc.value && !hadLocation ? tr('Set the shop location', 'দোকানের লোকেশন দিন')
              : !open24.value && (!openAt.value || !closeAt.value || openAt.value === closeAt.value) ? tr('Set opening and closing time', 'খোলা আর বন্ধের সময় দিন')
                : disc > 30 ? tr('Discount can be up to 30%', 'ছাড় সর্বোচ্চ ৩০%')
                  : !ex && missingDocs(specs, docs.value) ? missingDocs(specs, docs.value)
                    : !agreed.value ? tr('Please accept the terms', 'শর্তে সম্মতি দিন') : null;
  err.value = e;
  if (e || !place.value) return;
  busy.value = true;
  try {
    const r = await call<{ pending?: boolean }>('pharmacy_save', {
      name: name.value.trim(), licenseNo: licenseNo.value.trim(), phone: mobile, district: place.value.district, upazilaId: place.value.upazilaId ?? null, address: address.value.trim(),
      lat: loc.value?.lat ?? null, lng: loc.value?.lng ?? null, open24: open24.value, openAt: openAt.value, closeAt: closeAt.value, delivery: delivery.value, deliveryNote: deliveryNote.value.trim(), discount: disc,
      discountMin: takaOf(discountMin.value), freeDeliveryOver: takaOf(freeOver.value), discountNote: discountNote.value.trim(),
      closedDays: [...closedDays.value].sort((a, b) => a - b), termsAccepted: true, docs: docs.value,
    });
    after();
    emit('done', r.pending ? sentForApproval('pharmacy') : tr('Saved', 'সেভ হয়েছে'));
  } catch (x) {
    err.value = errorText(x);
    busy.value = false;
  }
}
const digits = (v: string, n: number) => toAsciiDigits(v).replace(/\D/g, '').slice(0, n);
</script>

<template>
  <div v-if="existing" class="row wrap gap-8">
    <StatusTag :status="existing.status" />
    <StatusReason :status="existing.status" :reason="existing.statusReason" />
  </div>
  <TextInput v-model="name" :label="tr('Pharmacy name', 'ফার্মেসির নাম')" maxlength="100" autocapitalize="words" />
  <div class="work-two">
    <TextInput v-model="licenseNo" :label="tr('Drug licence number', 'ড্রাগ লাইসেন্স নম্বর')" maxlength="40" />
    <TextInput :label="tr('Shop phone', 'দোকানের ফোন')" inputmode="tel" :model-value="phone" @update:model-value="(v) => (phone = v.replace(/[^\d+০-৯]/g, '').slice(0, 15))" />
  </div>
  <AreaField :label="tr('Area', 'এলাকা')" :value="place" @change="(v) => (place = v)" />
  <TextInput v-model="address" :label="tr('Shop address', 'দোকানের ঠিকানা')" maxlength="200" />
  <div class="col gap-6">
    <InfoText v-if="loc" :text="tr('Shop location set', 'দোকানের লোকেশন নেওয়া হয়েছে')" />
    <template v-else>
      <span class="t-body-s muted">{{ hadLocation ? tr('Update only if the shop moved.', 'দোকান সরালে তবেই আবার দিন।') : tr('Stand inside the shop and tap the button.', 'দোকানের ভেতরে দাঁড়িয়ে বাটন চাপুন।') }}</span>
      <Button variant="outline" icon="location_on" :busy="gps" class="work-self-start" @click="useGps">{{ tr('Use current location as shop location', 'বর্তমান লোকেশনকে দোকানের লোকেশন ধরুন') }}</Button>
    </template>
    <ErrorText :text="gpsErr" />
  </div>
  <Switch v-model="open24" :label="tr('Open 24 hours', '২৪ ঘণ্টা খোলা')" />
  <TimeRange v-if="!open24" :open-at="openAt" :close-at="closeAt" @change="(o, c) => { openAt = o; closeAt = c; }" />
  <WeekdayChips :value="closedDays" @change="(v) => (closedDays = v)" />
  <Switch v-model="delivery" :label="tr('Home delivery', 'বাসায় ডেলিভারি দিই')" />
  <TextInput v-if="delivery" v-model="deliveryNote" :label="tr('Delivery area / charge (optional)', 'ডেলিভারির এলাকা / চার্জ (ঐচ্ছিক)')" maxlength="120" />
  <TextInput :label="tr('Usual discount on MRP (%)', 'সাধারণত MRP থেকে কত % ছাড়')" inputmode="numeric" :model-value="discount" @update:model-value="(v) => (discount = digits(v, 2))" />
  <div class="work-two">
    <TextInput :label="tr('Discount only above (Tk, optional)', 'ছাড় কত টাকার বেশি কিনলে (৳, ঐচ্ছিক)')" inputmode="numeric" :model-value="discountMin" placeholder="৫০০" @update:model-value="(v) => (discountMin = digits(v, 6))" />
    <TextInput :label="tr('Free delivery above (Tk, optional)', 'কত টাকার বেশিতে ডেলিভারি ফ্রি (৳, ঐচ্ছিক)')" inputmode="numeric" :model-value="freeOver" placeholder="১০০০" @update:model-value="(v) => (freeOver = digits(v, 6))" />
  </div>
  <TextInput
    v-model="discountNote"
    :label="tr('Discount conditions (optional)', 'ছাড়ের শর্ত (ঐচ্ছিক)')"
    :hint="tr('e.g. cash only — shown on your card in the pharmacy list', 'যেমন: শুধু নগদে — ফার্মেসির তালিকায় আপনার কার্ডে দেখাবে')"
    maxlength="80"
  />
  <DocPhotosField :specs="pharmacyDocs()" :photos="docs" :note="existing ? PAPERS_CHANGED() : null" @change="(d) => (docs = d)" />
  <TermsCheck v-model="agreed" />
  <ErrorText :text="err" />
  <div class="form-actions">
    <Button variant="text" :disabled="busy" @click="nav(existing ? '/work/pharmacy/shop' : '/work/pharmacy')">{{ tr('Cancel', 'বাতিল') }}</Button>
    <Button :busy="busy" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
  </div>
</template>
