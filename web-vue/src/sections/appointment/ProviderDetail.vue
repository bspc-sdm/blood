<script setup lang="ts">
// ডাক্তার/নার্সের বিস্তারিত: সব চেম্বার, সিরিয়ালের ফোন, অ্যাপয়েন্টমেন্ট, হোম ভিজিট (preview: নিজে দেখছেন, বাটন কাজ করে না)
import { computed, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { Button, Dialog, Icon, IconButton, InfoText, PhoneLine, cx, toast } from '@/ui/kit';
import { useTwoPane } from '@/shell/chrome';
import { call } from '@/lib/api';
import { useSession } from '@/lib/session';
import { isBangla, num, tr } from '@/lib/i18n';
import { upazila } from '@/lib/geo';
import { badgeIcon, badgeLabel, chamberArea, chamberLabel, chamberTimeText, coverageText, feeText, homeServiceLabel, ratingLine, specialtyLabel, timesText, type Provider } from './model';
import DetailHead from './DetailHead.vue';
import DetailBlock from './DetailBlock.vue';
import EmergencyNotice from './EmergencyNotice.vue';
import ReasonReportDialog from './ReasonReportDialog.vue';
import ChamberPicker from './ChamberPicker.vue';
import ChamberChoices from './ChamberChoices.vue';
import BookDialog from './BookDialog.vue';

const props = defineProps<{ p: Provider; onClose?: () => void; onBooked?: (msg: string) => void; preview?: boolean }>();
const two = useTwoPane();
const s = useSession();
const booking = ref<number | null>(null);
const reporting = ref(false);
const reported = ref(false);
const done = ref<string | null>(null);
const choosing = ref(false);
const hv = computed(() => (props.p.homeVisit && props.p.homeVisit.enabled && props.p.homeVisit.phone.trim() ? props.p.homeVisit : null));
// ২.২৭.০ নিচের আটকানো বার (অ্যাপের DetailSheet-এর মতো): অ্যাপয়েন্টমেন্ট নেওয়া যায় এমন চেম্বার একটা হলে সরাসরি, কয়েকটা হলে
// কোন চেম্বার জিজ্ঞেস করে; সিরিয়ালের ফোন সব চেম্বারে একই হলে পাশে "ফোন" — তখন চেম্বারের ভেতরে আর আলাদা নেই
const bookable = computed(() => (props.p.appointments ? props.p.chambers.map((c, i) => (c.appointments ? i : -1)).filter((i) => i >= 0) : []));
const pinnedCall = computed(() => {
  const serialPhones = [...new Set(props.p.chambers.map((c) => c.serialPhone).filter(Boolean))];
  return serialPhones.length === 1 ? serialPhones[0] : null;
});
const book = (i: number) => !props.preview && s.requireLogin(tr('Log in to request an appointment', 'অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন'), () => (booking.value = i));
const ups = computed(() => (hv.value ? hv.value.upazilaIds.map((id) => upazila(id)).filter(Boolean).map((u) => (isBangla() ? u!.bn : u!.en)) : []));
const canReport = computed(() => !props.preview && !s.guest && props.p.uid !== s.uid && !reported.value);

async function report(reason: string) {
  await call('report', { kind: 'provider', id: props.p.uid, reason });
  reporting.value = false;
  reported.value = true;
}
function booked() {
  booking.value = null;
  const m = tr("Request sent. You'll be notified when it's confirmed.", 'অনুরোধ পাঠানো হয়েছে। নিশ্চিত হলে নোটিফিকেশন পাবেন।');
  done.value = m;
  toast(m);
  props.onBooked?.(m);
}
function mainBook() {
  if (bookable.value.length === 1) book(bookable.value[0]);
  else if (!props.preview) choosing.value = true;
}
function homeCalled() {
  if (!s.guest) call('home_called', { uid: props.p.uid }).catch(() => {});
}
</script>

<template>
  <div class="row top">
    <div class="grow">
      <DetailHead :title="p.name" :icon="badgeIcon(p.type)" :photo="p.photo || null" :subtitle="p.specialties.map(specialtyLabel).join(' · ')" :meta="ratingLine(p.rating, p.ratingCount)" />
    </div>
    <IconButton v-if="two && onClose && !preview" icon="close" :label="tr('Close', 'বন্ধ')" @click="onClose()" />
  </div>
  <!-- ২.৩৩.০: নামের নিচেই চেম্বার বাছাই — চাপলে সেই চেম্বারেই অ্যাপয়েন্টমেন্ট ("কোন চেম্বারে?" ছাড়া) -->
  <ChamberPicker :p="p" :bookable="bookable" :preview="preview" @book="book" />
  <InfoText :text="done" />
  <RouterLink v-if="done" class="btn tonal small has-icon" to="/my-appointments" :style="{ alignSelf: 'flex-start' }"><Icon name="event_outline" />{{ tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট') }}</RouterLink>
  <div v-for="(c, i) in p.chambers" :key="i" class="col">
    <hr class="divider" />
    <DetailBlock
      :title="chamberLabel(c)"
      :lines="[
        ['location_on', chamberArea(c)],
        ['info', c.room ? tr('Room: ', 'রুম: ') + c.room : ''],
        ['schedule', chamberTimeText(c)],
        ['payments', feeText(c.noFee, c.fee)],
      ]"
    >
      <Button v-if="bookable.length > 1 && bookable.includes(i)" square icon="calendar_month" @click="book(i)">
        {{ tr('Appointment at this chamber', 'এই চেম্বারে অ্যাপয়েন্টমেন্ট') }}
      </Button>
    </DetailBlock>
    <div v-if="c.serialPhone && !pinnedCall" class="appt-phone">
      <span class="t-label-m muted">{{ tr('Call for serial', 'সিরিয়ালের জন্য ফোন') }}</span>
      <span v-if="preview" class="t-title-m num">{{ num(c.serialPhone) }}</span>
      <PhoneLine v-else :phone="c.serialPhone" />
    </div>
  </div>
  <div v-if="hv" class="col">
    <hr class="divider" />
    <DetailBlock
      :title="tr('Home visit', 'বাসায় গিয়ে সেবা')"
      :lines="[
        ['medical_services', hv.services.map(homeServiceLabel).join(' · ')],
        ['location_on', coverageText(hv.radiusKm, ups)],
        ['schedule', timesText(hv.times, hv.schedule)],
        ['payments', hv.noFee ? tr('No fee', 'ফি নেই') : hv.fee ? tr('Fee: ', 'ফি: ') + hv.fee : ''],
      ]"
    />
    <div class="appt-phone">
      <span class="t-label-m muted">{{ tr('Call to visit home', 'বাসায় আসতে ফোন') }}</span>
      <span v-if="preview" class="t-title-m num">{{ num(hv.phone) }}</span>
      <PhoneLine v-else :phone="hv.phone" :on-call="homeCalled" />
    </div>
  </div>
  <hr class="divider" />
  <DetailBlock :title="tr('About', 'পরিচিতি')" :lines="[['school', p.degrees], ['badge', badgeLabel(p.type)]]" />
  <p v-if="preview" class="t-body-s muted">{{ tr('This is how patients see you. Save to update it.', 'রোগীরা আপনাকে এভাবেই দেখবেন। বদল দেখাতে সেভ করুন।') }}</p>
  <InfoText v-if="reported" :text="tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।')" />
  <EmergencyNotice k="doctor" />
  <div v-if="canReport && !(bookable.length || pinnedCall)" class="row">
    <span class="grow" />
    <Button variant="text" icon="flag" @click="reporting = true">{{ tr('Report', 'রিপোর্ট') }}</Button>
  </div>
  <div v-if="bookable.length || pinnedCall" class="detail-bar">
    <div class="detail-bar-row call-compact">
      <template v-if="pinnedCall">
        <span v-if="preview" :class="cx('btn outline square has-icon call', !bookable.length && 'solo')"><Icon name="call" />{{ num(pinnedCall) }}</span>
        <a v-else :class="cx('btn outline square has-icon call', !bookable.length && 'solo')" :href="`tel:${pinnedCall}`"><Icon name="call" /><span :class="bookable.length ? 'call-long' : undefined">{{ tr('Call for serial', 'সিরিয়ালের ফোন') }}</span><span v-if="bookable.length" class="call-short">{{ tr('Call', 'ফোন') }}</span></a>
      </template>
      <Button v-if="bookable.length" square icon="calendar_month" :class="cx(!pinnedCall && 'solo')" @click="mainBook">
        {{ tr('Appointment', 'অ্যাপয়েন্টমেন্ট') }}
      </Button>
    </div>
    <div v-if="pinnedCall || canReport" class="row services-acts">
      <span v-if="pinnedCall" class="t-body-m muted num">{{ num(pinnedCall) }}</span>
      <span class="grow" />
      <Button v-if="canReport" variant="text" icon="flag" @click="reporting = true">{{ tr('Report', 'রিপোর্ট') }}</Button>
    </div>
  </div>
  <Dialog v-if="choosing" size="small" :title="tr('Which chamber?', 'কোন চেম্বারে?')" @close="choosing = false">
    <ChamberChoices :p="p" :bookable="bookable" @pick="(i) => { choosing = false; book(i); }" />
    <template #actions><Button variant="text" @click="choosing = false">{{ tr('Cancel', 'বাতিল') }}</Button></template>
  </Dialog>
  <ReasonReportDialog v-if="reporting" :on-submit="report" @close="reporting = false" />
  <BookDialog v-if="booking != null && p.chambers[booking]" :provider="p" :index="booking" @close="booking = null" @booked="booked" />
</template>
