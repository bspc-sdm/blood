<script setup lang="ts">
// চেম্বার ও প্রোফাইল সেটআপ (অ্যাপের ChamberEditScreen / ChamberEditor / TimeSlotsEditor / HomeCareTermsCheck)।
// ডাক্তার: হাসপাতালের তালিকা থেকে চেম্বার; পুষ্টিবিদ/ট্রেইনার: নিজের জায়গা লিখে; নার্স: চেম্বার নেই, শুধু বাসায় সেবা।
import { computed, ref, watch } from 'vue';
import { Button, Check, ChoiceChips, Dialog, ErrorText, Icon, InfoText, SkeletonCards, Switch, TextInput, toast } from '@/ui/kit';
import { call, errorText, ServerError } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { isBangla, num, tr } from '@/lib/i18n';
import { currentPosition, districtAt, districtLabel, upazila } from '@/lib/geo';
import { HOME_RADII, HOME_TYPES, MAX_CHAMBERS, chamberLabel, clash, homeServiceLabel, homeServicesOf, parseProvider, slotsOf, specialtiesOf, specialtyLabel, type Hospital, type HomeVisit, type Provider } from '../model';
import type { AreaSel } from '../shared';
import AreaSheet from '../AreaSheet.vue';
import HospitalPickerDialog from '../HospitalPickerDialog.vue';
import PhoneVerifyDialog from '../PhoneVerifyDialog.vue';
import ProviderDetail from '../ProviderDetail.vue';
import { TERMS_URL, blankChamber, blankOnline, chamberJson, emptyHome, homeJson, newSlot, nextKey, type Ed } from './chamber';
import { T as OT, hasPhone, normBkash } from '../online';
import { onlineOn } from '../online/flags';
import ChamberEditor from './ChamberEditor.vue';
import TimeSlotsEditor from './TimeSlotsEditor.vue';
import UpazilaPicker from './UpazilaPicker.vue';
import HomeStatusNote from './HomeStatusNote.vue';

const props = defineProps<{ type: string }>();
const emit = defineEmits<{ saved: [] }>();
const s = useSession();
const pm = useCall<{ provider: any | null }>('provider_me');
const nurse = computed(() => props.type === 'nurse');
const homeCapable = computed(() => HOME_TYPES.includes(props.type));

const listed = ref(true);
const takes = ref(true);
const specialties = ref<string[]>([]);
const degrees = ref('');
const gender = ref('');
const chambers = ref<Ed[]>([]);
const home = ref<HomeVisit>(emptyHome());
const homeAgreed = ref(false);
const oldChamber = ref<string | null>(null);
// সেভ করা অনলাইন চেম্বারের bKash নম্বর (key ধরে) — বদলালে ২৪ ঘণ্টা পর চালু, সম্পাদকে লেখা
const origBkash = ref<Record<number, string>>({});
const gpsPlace = ref<string | null>(s.profile?.locationSource === 'gps' ? s.profile.placeName : null);
let loaded = false;

watch(
  () => pm.data,
  (data) => {
    if (loaded || !data) return;
    loaded = true;
    const p = data.provider ? parseProvider(data.provider) : null;
    if (!p) return;
    listed.value = p.listed;
    takes.value = p.appointments;
    specialties.value = p.specialties;
    degrees.value = p.degrees;
    gender.value = p.gender;
    // আগের ভার্সনের লেখা চেম্বার (হাসপাতালের সাথে যুক্ত নয়, নিজের জায়গাও নয়) আবার বেছে নিতে হবে
    // অনলাইন চেম্বারে হাসপাতাল থাকে না — সেটা আগের ভার্সনের নয়
    chambers.value = p.chambers.filter((c) => c.hospitalId != null || c.custom || c.mode === 'online').map((c) => ({ ...c, key: nextKey() }));
    origBkash.value = Object.fromEntries(chambers.value.filter((c) => c.mode === 'online' && c.bkashNumber).map((c) => [c.key, c.bkashNumber]));
    const old = p.chambers.find((c) => c.hospitalId == null && !c.custom && c.mode !== 'online');
    if (old) oldChamber.value = old.hospitalName;
    if (p.homeVisit) {
      home.value = p.homeVisit;
      homeAgreed.value = p.homeVisit.enabled && !!p.homeVisit.phone;
    }
  },
  { immediate: true },
);

const pickFor = ref<number | null>(null); // -1 = নতুন চেম্বার
const districtFor = ref<number | null>(null);
const pickUpazila = ref(false);
const preview = ref(false);
const busy = ref(false);
const gpsBusy = ref(false);
const err = ref<string | null>(null);
const verifyPhone = ref(false);

const setCh = (i: number, c: Ed) => (chambers.value = chambers.value.map((x, j) => (j === i ? c : x)));
const setHome = (patch: Partial<HomeVisit>) => (home.value = { ...home.value, ...patch });

function validate(): string | null {
  const ch = chambers.value;
  const h = home.value;
  const named = (i: number) => chamberLabel(ch[i]);
  const homeTimes = h.times.length ? h.times : [newSlot()];
  if (!specialties.value.length) return tr('Choose at least one specialty', 'অন্তত একটা বিশেষজ্ঞতা বেছে নিন');
  if (nurse.value && !h.enabled) return tr('Turn on home care', 'বাসায় সেবা চালু করুন');
  if (!homeCapable.value && !ch.length) return tr('Add a chamber', 'অন্তত একটা চেম্বার যোগ করুন');
  if (!nurse.value && !ch.length && !h.enabled) return tr('Add a chamber, or turn on home care', 'অন্তত একটা চেম্বার যোগ করুন, অথবা বাসায় সেবা চালু করুন');
  if (!nurse.value) {
    if (ch.some((c) => c.mode !== 'online' && !c.custom && !c.hospitalId)) return OT.errHospital();
    if (ch.some((c) => c.mode !== 'online' && c.custom && (c.hospitalName.trim().length < 3 || !c.district || c.address.trim().length < 5))) return tr('Write the place name, district and address', 'জায়গার নাম, জেলা আর ঠিকানা লিখুন');
    if (ch.some((c) => slotsOf(c).some((t) => !t.days.length))) return tr('Choose the days for each chamber', 'প্রতিটা চেম্বারে কোন কোন বার বসেন বেছে নিন');
    if (ch.some((c) => slotsOf(c).some((t) => !t.start || !t.end || t.start === t.end))) return tr('Set the start and end time for each chamber', 'প্রতিটা চেম্বারে শুরু আর শেষের সময় দিন');
    const cl = clash(ch.map((c) => ({ slots: slotsOf(c) })));
    if (cl) {
      const [x, y] = cl;
      return x === y
        ? tr(`Two times in "${named(x)}" overlap on the same day`, `"${named(x)}" চেম্বারে একই বারে দুটো সময় মিলে যাচ্ছে`)
        : tr(`"${named(x)}" and "${named(y)}" overlap on the same day and time`, `"${named(x)}" আর "${named(y)}" একই বারে একই সময়ে পড়েছে — একসাথে দুই জায়গায় বসা যায় না`);
    }
    if (ch.some((c) => !c.fee.trim() && !c.noFee)) return tr('Enter the fee for each chamber, or tick "No fee"', 'প্রতিটা চেম্বারে ফি দিন, অথবা "ফি চাই না" টিক দিন');
    if (ch.some((c) => c.mode === 'online' && !c.noFee && !normBkash(c.bkashNumber))) return OT.errBkash();
    if (ch.some((c) => c.mode === 'online' && hasPhone(c.note))) return OT.errNotePhone();
    if (ch.filter((c) => c.mode === 'online').length > 1) return OT.errOneOnline();
  }
  if (h.enabled) {
    if (!h.services.length) return tr('Choose the services you provide at home', 'বাসায় কোন কোন সেবা দেন বেছে নিন');
    if (homeTimes.some((t) => !t.days.length)) return tr('Choose the days you visit homes', 'কোন কোন বার বাসায় যান বেছে নিন');
    if (homeTimes.some((t) => !t.start || !t.end || t.start === t.end)) return tr('Set the start and end time for home visits', 'বাসায় যাওয়ার শুরু আর শেষের সময় দিন');
    if (clash([{ slots: homeTimes }])) return tr('Two home-visit times overlap on the same day', 'বাসায় যাওয়ার দুটো সময় একই বারে মিলে যাচ্ছে');
    if (!h.fee.trim() && !h.noFee) return tr('Enter the home care fee, or tick "No fee"', 'বাসায় সেবার ফি দিন, অথবা "ফি চাই না" টিক দিন');
    if (!/^(\+?88)?0(1[3-9]\d{8}|120000\d{4})$/.test(h.phone)) return tr('Enter a valid mobile number for home care', 'বাসায় সেবার জন্য সঠিক মোবাইল নম্বর দিন');
    if (!homeAgreed.value) return tr('Please accept the home care terms', 'বাসায় সেবার শর্তে সম্মতি দিন');
    if (!gpsPlace.value && !h.upazilaIds.length) return tr('Set your current location or choose upazilas for home care', 'বাসায় সেবার জন্য বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন');
  }
  return null;
}

async function save() {
  const e = validate();
  err.value = e;
  if (e) return;
  busy.value = true;
  try {
    const r = await call<{ ok: boolean; cancelled?: number }>('provider_save', {
      listed: listed.value && !nurse.value,
      appointments: takes.value && !nurse.value,
      specialties: specialties.value,
      degrees: degrees.value.trim(),
      gender: gender.value,
      chambers: nurse.value ? [] : chambers.value.map(chamberJson),
      homeVisit: homeJson(homeCapable.value ? home.value : emptyHome()),
    });
    const n = r.cancelled ?? 0;
    toast(
      n > 0
        ? tr(`${n} upcoming appointment(s) no longer fit and were cancelled. Patients were told.`, `সামনের ${num(n)}টা অ্যাপয়েন্টমেন্ট আর মেলে না, তাই বাতিল হয়েছে। রোগীদের জানানো হয়েছে।`)
        : tr('Saved.', 'সেভ হয়েছে।'),
    );
    pm.refetch();
    emit('saved');
  } catch (x) {
    if (x instanceof ServerError && x.code === 'phone_unverified') verifyPhone.value = true;
    else err.value = errorText(x);
  } finally {
    busy.value = false;
  }
}

async function useGps() {
  gpsBusy.value = true;
  err.value = null;
  try {
    const { lat, lng } = await currentPosition();
    const d = await districtAt(lat, lng);
    await s.setPlace({ lat, lng, placeName: d.bn, source: 'gps' });
    gpsPlace.value = d.bn;
  } catch (x) {
    err.value = x instanceof Error && x.message && !/denied|no-geo/i.test(x.message) ? x.message : tr("Couldn't get your location.", 'লোকেশন পাওয়া যায়নি।');
  } finally {
    gpsBusy.value = false;
  }
}

// রোগীরা তালিকায় চাপলে যেমন দেখবেন (সেভ না করেও, এখনকার বদলসহ)
const previewProvider = computed<Provider>(() => ({
  uid: s.uid ?? '', type: props.type, name: s.profile?.name ?? '', specialties: specialties.value, degrees: degrees.value.trim(), gender: gender.value, listed: listed.value, appointments: takes.value,
  chambers: nurse.value ? [] : chambers.value, homeVisit: homeCapable.value ? { ...home.value, phone: home.value.phone || ' ' } : null, rating: 0, ratingCount: 0, photo: '',
}));

function addChamber() {
  // ডাক্তার: হাসপাতালের তালিকা থেকে; পুষ্টিবিদ/ট্রেইনার: নিজের জায়গা লিখে (হাসপাতালে বসেন না)
  if (props.type === 'doctor') pickFor.value = -1;
  else chambers.value = [...chambers.value, { ...blankChamber(s.profile?.placeName ?? '', true), key: nextKey() }];
}
// অনলাইন (ভিডিও) চেম্বার — শুধু ডাক্তার (চুক্তি: "Online chambers (doctor)"); bKash আগে থেকে নিজের ফোন নম্বর
const canOnline = computed(() => props.type === 'doctor' && onlineOn());
// সর্বোচ্চ ৩টা জায়গার চেম্বার, তার বাইরে একটা অনলাইন (সার্ভারের নিয়ম)
const placeCount = computed(() => chambers.value.filter((c) => c.mode !== 'online').length);
function addOnline() {
  chambers.value = [...chambers.value, { ...blankOnline(normBkash(s.profile?.phone ?? '') ?? ''), key: nextKey() }];
}
function hospitalPicked(h: Hospital) {
  const i = pickFor.value!;
  const fresh = { hospitalId: h.id, hospitalName: h.nameEn, hospitalNameBn: h.nameBn, district: h.district, upazilaId: h.upazilaId, unionId: h.unionId };
  if (i >= 0 && chambers.value[i]) setCh(i, { ...chambers.value[i], ...fresh });
  else chambers.value = [...chambers.value, { ...blankChamber(h.district, false), ...fresh, appointments: takes.value, key: nextKey() }];
  pickFor.value = null;
}
function districtPicked(v: AreaSel) {
  const i = districtFor.value!;
  if (v.district) setCh(i, { ...chambers.value[i], district: v.district, upazilaId: v.upazilaId });
  districtFor.value = null;
}
function upazilaPicked(id: number) {
  if (!home.value.upazilaIds.includes(id)) setHome({ upazilaIds: [...home.value.upazilaIds, id] });
  pickUpazila.value = false;
}
function phoneVerified() {
  verifyPhone.value = false;
  err.value = tr('Phone verified. Tap Save again.', 'ফোন যাচাই হয়েছে। আবার সেভ চাপুন।');
}
const upName = (id: number) => {
  const u = upazila(id);
  return u ? (isBangla() ? u.bn : u.en) : null;
};
</script>

<template>
  <SkeletonCards v-if="pm.isLoading" :n="3" :h="140" />
  <ErrorText v-else-if="pm.error" :text="errorText(pm.error)" />
  <div v-else class="col gap-16 appt-setup">
    <div class="row wrap">
      <Button variant="outline" icon="info" @click="preview = true">{{ tr('See how patients see it', 'রোগীরা যেমন দেখবেন') }}</Button>
    </div>
    <section class="card lowest col gap-12 appt-sec-card">
      <h2 class="t-title-m">{{ tr('Profile', 'প্রোফাইল') }}</h2>
      <div v-if="!nurse" class="col gap-2">
        <Switch v-model="listed" :label="tr('Show me in the list', 'তালিকায় আমাকে দেখাও')" />
        <Switch v-model="takes" :label="tr('Take appointments through the app', 'অ্যাপে অ্যাপয়েন্টমেন্ট নেব')" />
      </div>
      <div class="col gap-6">
        <span class="t-title-s">{{ tr('Specialty', 'বিশেষজ্ঞতা') }}</span>
        <ChoiceChips
          multi
          :options="specialtiesOf(type).map((c) => ({ value: c, label: specialtyLabel(c) }))"
          :model-value="specialties"
          @update:model-value="(v: string[]) => (specialties = v.slice(0, 5))"
        />
      </div>
      <TextInput :label="tr('Degrees (optional)', 'ডিগ্রি (ঐচ্ছিক)')" :placeholder="tr('e.g. MBBS, FCPS (Medicine)', 'যেমন: MBBS, FCPS (Medicine)')" :model-value="degrees" @update:model-value="(v) => (degrees = v.slice(0, 120))" />
      <div class="col gap-6">
        <span class="t-label-l">{{ tr('Gender (optional)', 'লিঙ্গ (ঐচ্ছিক)') }}</span>
        <ChoiceChips v-model="gender" :options="[{ value: '', label: tr('Not set', 'দেখাব না') }, { value: 'female', label: tr('Female', 'মহিলা') }, { value: 'male', label: tr('Male', 'পুরুষ') }]" />
      </div>
    </section>

    <section v-if="!nurse" class="col gap-12">
      <h2 class="t-title-m">{{ tr('Chambers (up to 3)', 'চেম্বার (সর্বোচ্চ ৩টা)') }}</h2>
      <InfoText
        v-if="oldChamber"
        :text="tr(`Your earlier chamber &quot;${oldChamber}&quot; now needs to be chosen from the hospital list. Tap Add chamber.`, `আগের চেম্বার &quot;${oldChamber}&quot; এখন হাসপাতালের তালিকা থেকে বেছে নিতে হবে। &quot;চেম্বার যোগ করুন&quot; চাপুন।`)"
      />
      <div class="appt-ch-grid">
        <ChamberEditor
          v-for="(c, i) in chambers"
          :key="c.key"
          :c="c"
          :can-online="canOnline"
          :orig-bkash="origBkash[c.key] ?? null"
          @change="(n) => setCh(i, { ...n, key: c.key })"
          @change-hospital="pickFor = i"
          @district="districtFor = i"
          @remove="chambers = chambers.filter((_, j) => j !== i)"
        />
        <button v-if="placeCount < MAX_CHAMBERS" type="button" class="appt-add-ch" @click="addChamber"><Icon name="add" />{{ tr('Add chamber', 'চেম্বার যোগ করুন') }}</button>
        <button v-if="canOnline && !chambers.some((c) => c.mode === 'online')" type="button" class="appt-add-ch" data-testid="add-online" @click="addOnline"><Icon name="language" />{{ OT.addOnline() }}</button>
      </div>
    </section>

    <section v-if="homeCapable" class="card lowest col gap-12 appt-sec-card">
      <h2 class="t-title-m">{{ tr('Home care', 'বাসায় সেবা') }}</h2>
      <HomeStatusNote />
      <Switch
        :label="tr('I visit patients at home', 'আমি রোগীর বাসায় গিয়ে সেবা দিই')"
        :model-value="home.enabled"
        @update:model-value="(on) => setHome({ enabled: on, phone: home.phone || s.profile?.phone || '' })"
      />
      <template v-if="home.enabled">
        <TextInput
          :label="tr('Phone patients will call', 'রোগীরা যে নম্বরে ফোন করবেন')"
          :hint="tr('Shown to everyone in the list', 'তালিকায় সবাই দেখবেন')"
          inputmode="tel"
          :model-value="home.phone"
          @update:model-value="(v) => setHome({ phone: v.replace(/[^0-9+]/g, '').slice(0, 14) })"
        />
        <div class="col gap-6">
          <span class="t-label-l">{{ tr('Services', 'কী কী সেবা দেন') }}</span>
          <ChoiceChips multi :options="homeServicesOf(type).map((c) => ({ value: c, label: homeServiceLabel(c) }))" :model-value="home.services" @update:model-value="(v: string[]) => setHome({ services: v })" />
        </div>
        <div class="col gap-6">
          <span class="t-label-l">{{ tr('How far from your location', 'আপনার লোকেশন থেকে কত দূর') }}</span>
          <ChoiceChips :options="HOME_RADII.map((r) => ({ value: r, label: tr(`${r} km`, `${num(r)} কিমি`) }))" :model-value="home.radiusKm" @update:model-value="(v: number) => setHome({ radiusKm: v })" />
          <span v-if="gpsPlace" class="t-body-s muted">{{ tr('Location: ', 'লোকেশন: ') + districtLabel(gpsPlace) }}</span>
          <Button variant="outline" icon="location_on" :busy="gpsBusy" :style="{ alignSelf: 'flex-start' }" @click="useGps">
            {{ gpsPlace ? tr('Update location', 'লোকেশন আপডেট') : tr('Use my current location', 'বর্তমান লোকেশন দিন') }}
          </Button>
        </div>
        <div class="col gap-6">
          <span class="t-label-l">{{ tr('Also these upazilas (optional)', 'এই উপজেলাগুলোতেও (ঐচ্ছিক)') }}</span>
          <div class="chips">
            <button
              v-for="id in home.upazilaIds"
              :key="id"
              type="button"
              class="chip on"
              :aria-label="tr('Remove', 'সরান') + ': ' + (upName(id) ?? id)"
              @click="setHome({ upazilaIds: home.upazilaIds.filter((x) => x !== id) })"
            >{{ upName(id) ?? num(id) }}<Icon name="close" /></button>
            <button v-if="home.upazilaIds.length < 10" type="button" class="chip lead" @click="pickUpazila = true"><Icon name="add" />{{ tr('Add', 'যোগ') }}</button>
          </div>
        </div>
        <div class="col gap-6">
          <span class="t-title-s">{{ tr('When you visit', 'কখন যেতে পারেন') }}</span>
          <TimeSlotsEditor :slots="home.times.length ? home.times : [newSlot()]" @change="(times) => setHome({ times })" />
        </div>
        <TextInput :label="tr('Note (optional)', 'নোট (ঐচ্ছিক)')" :placeholder="tr('e.g. Call an hour before', 'যেমন: এক ঘণ্টা আগে ফোন দিন')" :model-value="home.schedule" @update:model-value="(v) => setHome({ schedule: v.slice(0, 120) })" />
        <TextInput :label="tr('Fee', 'ফি')" :placeholder="tr('e.g. 300 Tk + transport', 'যেমন: ৩০০ টাকা + যাতায়াত')" :disabled="home.noFee" :model-value="home.fee" @update:model-value="(v) => setHome({ fee: v.slice(0, 60) })" />
        <Check :model-value="home.noFee" @update:model-value="(v) => setHome({ noFee: v, fee: v ? '' : home.fee })">{{ tr('No fee', 'ফি চাই না') }}</Check>
        <div class="col gap-2">
          <Check v-model="homeAgreed">{{ tr('I accept the home care terms, and agree to show this number to patients', 'বাসায় সেবার শর্ত মেনে নিচ্ছি, আর এই নম্বরটা রোগীদের দেখাতে রাজি') }}</Check>
          <a :href="TERMS_URL" target="_blank" rel="noreferrer" class="t-body-s c-primary appt-terms">{{ tr('Read the terms', 'শর্তগুলো পড়ুন') }}</a>
        </div>
      </template>
    </section>

    <div class="appt-savebar">
      <ErrorText :text="err" />
      <div class="row">
        <span class="grow" />
        <Button :busy="busy" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
      </div>
    </div>

    <HospitalPickerDialog
      v-if="pickFor != null"
      :initial-district="chambers[pickFor]?.district || s.profile?.placeName || null"
      :initial-upazila-id="chambers[pickFor]?.upazilaId ?? null"
      @pick="hospitalPicked"
      @close="pickFor = null"
    />
    <AreaSheet
      v-if="districtFor != null && chambers[districtFor]"
      :value="{ district: chambers[districtFor].district || null, upazilaId: chambers[districtFor].upazilaId, unionId: null }"
      @pick="districtPicked"
      @close="districtFor = null"
    />
    <UpazilaPicker v-if="pickUpazila" :own="s.profile?.placeName ?? ''" :lat="s.profile?.lat ?? null" :lng="s.profile?.lng ?? null" @pick="upazilaPicked" @close="pickUpazila = false" />
    <Dialog v-if="preview" :title="tr('See how patients see it', 'রোগীরা যেমন দেখবেন')" size="wide" @close="preview = false">
      <div class="col gap-12">
        <ProviderDetail :p="previewProvider" preview />
      </div>
    </Dialog>
    <PhoneVerifyDialog v-if="verifyPhone" @close="verifyPhone = false" @verified="phoneVerified" />
  </div>
</template>
