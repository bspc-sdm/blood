<script setup lang="ts">
// প্রেসক্রিপশনের ভেতরে: ওষুধ কেনা (দোকানে / অনলাইনে, ফিরিয়ে নেওয়া), পরীক্ষা পাঠানো (কোথায় কয়টা হয়, আনুমানিক দাম,
// বাসা থেকে নমুনা), রিপোর্ট, ডাক্তারকে দেখানো — অ্যাপের RxNet.kt-এর RxActivitySection।
// paper slot দিলে: উপরে বড় "ফার্মেসিতে পাঠান" / "প্যাথলজিতে পাঠান", তারপর কাগজ, তারপর বাকিটা — লম্বা কাগজের নিচে বাটন খুঁজে পাওয়া যেত না
import { computed, ref } from 'vue';
import { Action, Button, ConfirmDialog, Dialog, ErrorText, IconLine, ListRow, SkeletonCards, Tag, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useQ, queryClient } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { homeEditable, homeStatusPatient, snapText } from '@/lib/labprice';
import type { Rx } from './rx';
import { OPEN, boughtText, labStatus, orderStatus, type Activity, type HomeReq, type LabOrder, type RxOrder, type RxReview } from './rxactivity';
import ReportReviewBox from './ReportReviewBox.vue';
import PharmacyPickDialog from './PharmacyPickDialog.vue';
import WithdrawDialog from './WithdrawDialog.vue';
import LabSendDialog from './LabSendDialog.vue';
import LabPickDialog from './LabPickDialog.vue';
import LabReportDialog from './LabReportDialog.vue';
import PayDialog from './PayDialog.vue';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';

const props = defineProps<{ rx: Rx }>();
const lang = useLang();
const care = useCareMode();
const s = useSession();
const q = useQ<Activity>(() => ({
  queryKey: ['rx_activity', props.rx.id, care.value?.uid ?? null, lang.value, s.uid],
  queryFn: () => call<Activity>('rx_activity', { rxId: props.rx.id }),
}));
// কাজ হলে টোস্ট (পর্দার নিচে) — আগে উপরের এক লাইনে যেত, নিচ থেকে পাঠালে স্ক্রল না করে দেখা যেত না
const setInfo = (m: string) => toast(m);
const err = ref<string | null>(null);
const pick = ref<'shop' | 'online' | null>(null);
const withdrawing = ref<RxOrder | null>(null);
const pickLab = ref(false);
const sendChoice = ref(false);
const homeEdit = ref<LabOrder | null>(null);
const homeCancel = ref<LabOrder | null>(null);
const homeBusy = ref(false);
const homeErr = ref<string | null>(null);
const report = ref<string | null>(null);
const paying = ref<RxReview | null>(null);
const serialBusy = ref(false);
const reload = () => queryClient.invalidateQueries({ queryKey: ['rx_activity', props.rx.id] });
const cancelled = computed(() => props.rx.status === 'cancelled');
const a = computed(() => q.data!);
// নিচের বাটনের মতোই শর্ত: বাতিল নয়, আর কোনো ওষুধ এখনো পুরো কেনা হয়নি / পরীক্ষা আছে
const canPharmacy = computed(() => !cancelled.value && !!props.rx.medicines?.length && !!q.data && q.data.medicines.some((m) => !m.done));
const canLab = computed(() => !cancelled.value && !!props.rx.tests?.length);

async function serial(date: number) {
  serialBusy.value = true;
  err.value = null;
  try {
    await call('book', { providerUid: props.rx.doctorUid, chamberIndex: props.rx.chamberIndex ?? 0, date, note: tr('Show the report', 'রিপোর্ট দেখানো') });
    setInfo(tr('Serial request sent', 'সিরিয়ালের অনুরোধ গেছে'));
  } catch (e) {
    err.value = errorText(e);
  }
  serialBusy.value = false;
}
const extraOf = (o: RxOrder) => [o.price, o.eta, o.deliveryFee ? tr(`Delivery: ${o.deliveryFee}`, `ডেলিভারি: ${o.deliveryFee}`) : ''].filter(Boolean);
const reviewOf = (o: LabOrder) => a.value.reviews.find((x) => x.labOrderId === o.id) ?? null;
function choose(m: 'shop' | 'online') {
  sendChoice.value = false;
  pick.value = m;
}
function onPharmacySent(name: string) {
  setInfo(pick.value === 'online' ? tr(`Order sent to ${name}. Pay when the medicines arrive.`, `${name}-এ অর্ডার গেছে। ওষুধ পৌঁছালে দাম দেবেন।`) : tr(`Sent to ${name}`, `${name}-এ পাঠানো হয়েছে`));
  pick.value = null;
  reload();
}
function onWithdrawn() {
  withdrawing.value = null;
  setInfo(tr('Taken back. You can send it to another pharmacy.', 'ফিরিয়ে নেওয়া হয়েছে। অন্য ফার্মেসিতে পাঠাতে পারেন।'));
  reload();
}
async function changeHome(home: HomeReq | null) {
  await call('lab_home_change', { id: homeEdit.value!.id, home });
  homeEdit.value = null;
  setInfo(tr('The lab was told the new time', 'প্যাথলজিকে নতুন সময় জানানো হয়েছে'));
  reload();
}
async function cancelHome() {
  homeBusy.value = true;
  homeErr.value = null;
  try {
    await call('lab_home_cancel', { id: homeCancel.value!.id });
    homeCancel.value = null;
    setInfo(tr('The lab was told — go there to give the sample', 'প্যাথলজিকে জানানো হয়েছে — সেখানে গিয়ে নমুনা দিন'));
    reload();
  } catch (e) {
    homeErr.value = errorText(e);
  }
  homeBusy.value = false;
}
function onLabSent(name: string) {
  pickLab.value = false;
  setInfo(tr(`Sent to ${name}`, `${name}-এ পাঠানো হয়েছে`));
  reload();
}
function onPaid() {
  paying.value = null;
  setInfo(tr('Sent. The doctor will check the payment.', 'পাঠানো হয়েছে। ডাক্তার টাকা মিলিয়ে দেখবেন।'));
  reload();
}
</script>

<template>
  <!-- লোড হওয়ার সময় উপরের বাটন নেই, কাগজ আগের মতোই দেখায় -->
  <template v-if="q.isPending"><slot name="paper" /><SkeletonCards :n="1" :h="120" /></template>
  <template v-else-if="q.isError"><slot name="paper" /><ErrorText :text="errorText(q.error)" /></template>
  <template v-else>
    <div v-if="canPharmacy || canLab" :class="canPharmacy && canLab ? 'health-two health-send' : 'col health-send'">
      <Button v-if="canPharmacy" icon="medication" block @click="sendChoice = true">{{ tr('Send to a pharmacy', 'ফার্মেসিতে পাঠান') }}</Button>
      <Button v-if="canLab" icon="local_hospital" block @click="pickLab = true">{{ tr('Send to a lab', 'প্যাথলজিতে পাঠান') }}</Button>
    </div>
    <ErrorText :text="err" />
    <slot name="paper" />
    <section v-if="rx.medicines?.length" class="card col" :aria-label="tr('Medicines bought', 'ওষুধ কেনা')">
      <h3 class="t-title-m">{{ tr('Medicines bought', 'ওষুধ কেনা') }}</h3>
      <div v-for="m in a.medicines" :key="m.i" class="row">
        <span class="t-body-l grow">{{ m.name }}</span>
        <Tag :tone="m.done ? 'good' : undefined">{{ boughtText(m) }}</Tag>
      </div>
      <span v-for="(d, i) in a.dispensed" :key="i" class="t-body-s muted">{{ d.name + ' · ' + formatDate(d.at) }}</span>
      <div v-for="o in a.orders" :key="o.id" class="col gap-4">
        <IconLine :icon="o.mode === 'online' ? 'local_shipping' : 'storefront'">{{ o.pharmacyName + ' — ' + orderStatus(o.status) + (o.reason ? ` (${o.reason})` : '') }}</IconLine>
        <span v-if="OPEN.includes(o.status) && extraOf(o).length" class="t-body-s muted">{{ extraOf(o).join(' · ') }}</span>
        <span v-if="o.missed?.length" class="t-body-s muted">{{ tr("Didn't have: ", 'ছিল না: ') + o.missed.join(', ') }}</span>
        <div v-if="['sent', 'ready', 'accepted'].includes(o.status)"><Action icon="close" @click="withdrawing = o">{{ tr('Take back', 'ফিরিয়ে নিন') }}</Action></div>
        <ContactActions :message="chatOn()" @message="openChatAt" ctx="rx_order" :ref-id="o.id" :peer-name="o.pharmacyName" :show="o.mode === 'online' && OPEN.includes(o.status)" />
      </div>
      <template v-if="!cancelled && a.medicines.some((m) => !m.done)">
        <!-- উপরের বড় বাটনের মতোই একটা — একই বাছাই (দোকানে আছি / অনলাইনে অর্ডার) -->
        <Button variant="outline" icon="medication" block @click="sendChoice = true">{{ tr('Send to a pharmacy', 'ফার্মেসিতে পাঠান') }}</Button>
        <span class="t-body-s muted">
          {{ tr(
            'At the shop: send the prescription to it in the app, or show the QR. They note what they give; get the rest from another shop.',
            'দোকানে গিয়ে: অ্যাপে সেই দোকানে পাঠান, অথবা QR দেখান। যতটা দেবে লিখে রাখবে; বাকিটা অন্য দোকান থেকে নিন।',
          ) }}
        </span>
      </template>
    </section>

    <section v-if="rx.tests?.length" class="card col" :aria-label="tr('Tests and reports', 'পরীক্ষা ও রিপোর্ট')">
      <h3 class="t-title-m">{{ tr('Tests and reports', 'পরীক্ষা ও রিপোর্ট') }}</h3>
      <div v-for="o in a.labOrders" :key="o.id" class="col gap-6 health-lab-order">
        <div class="row">
          <span class="t-body-l semi grow">{{ o.labName }}</span>
          <Tag :tone="o.status === 'reported' ? 'good' : undefined">{{ labStatus(o.status) }}</Tag>
        </div>
        <span class="t-body-s muted">{{ o.tests.join(', ') }}</span>
        <ContactActions :message="chatOn()" @message="openChatAt" ctx="lab_order" :ref-id="o.id" :peer-name="o.labName" :show="['sent', 'received'].includes(o.status)" />
        <!-- পাঠানোর সময়ের আনুমানিক দাম; তালিকায় না থাকা পরীক্ষা ("ECG দাম জানা নেই") -->
        <span v-if="o.prices && o.status !== 'cancelled'" class="t-body-s">{{ snapText(o.prices, o.home) }}</span>
        <IconLine
          v-if="o.home && ['sent', 'received'].includes(o.status)"
          icon="home_health"
          :color="o.home.status === 'declined' || o.home.status === 'expired' ? 'var(--error)' : o.home.status === 'confirmed' ? 'var(--primary)' : undefined"
        >{{ homeStatusPatient(o.home) }}</IconLine>
        <span v-if="o.home?.status === 'confirmed' && o.home.confirmNote && o.status === 'sent'" class="t-body-s muted">{{ o.home.confirmNote }}</span>
        <!-- প্যাথলজি সময় জানানোর আগে (সময় পেরিয়ে গেলেও): সময় বদলানো, বা বাসা থেকে নয় — প্যাথলজিতে গিয়ে -->
        <div v-if="!cancelled && homeEditable(o.home, o.status)" class="row wrap gap-8">
          <Action :primary="o.home?.status === 'expired'" icon="edit" @click="homeEdit = o">{{ tr('Change time', 'সময় বদলান') }}</Action>
          <Action icon="close" @click="() => { homeErr = null; homeCancel = o; }">{{ tr("I'll go to the lab", 'প্যাথলজিতে গিয়ে দেব') }}</Action>
        </div>
        <template v-if="o.status === 'reported'">
          <div><Action primary icon="list_alt" @click="report = o.id">{{ tr('See report', 'রিপোর্ট দেখুন') }}</Action></div>
          <ReportReviewBox :rx="rx" :r="reviewOf(o)" :next-open="a.nextOpen" :busy="serialBusy" @pay="(r) => (paying = r)" @serial="serial" />
        </template>
      </div>
      <Button v-if="!cancelled" variant="outline" icon="local_hospital" block @click="pickLab = true">{{ tr('Send tests to a lab', 'পরীক্ষা করাতে পাঠান') }}</Button>
    </section>

    <Dialog v-if="sendChoice" size="small" :title="tr('Send to a pharmacy', 'ফার্মেসিতে পাঠান')" @close="sendChoice = false">
      <div class="col gap-4">
        <ListRow icon="storefront" :title="tr('I\'m at a pharmacy — send to this shop', 'দোকানে আছি — এই দোকানে পাঠান')" @click="choose('shop')" />
        <ListRow icon="local_shipping" :title="tr('Order online — home delivery', 'অনলাইনে অর্ডার — বাসায় ডেলিভারি')" @click="choose('online')" />
      </div>
    </Dialog>
    <PharmacyPickDialog v-if="pick" :rx="rx" :mode="pick" @close="pick = null" @sent="onPharmacySent" />
    <WithdrawDialog v-if="withdrawing" :o="withdrawing" @close="withdrawing = null" @done="onWithdrawn" />
    <LabSendDialog v-if="homeEdit?.home" :title="homeEdit.labName" :change="homeEdit.home" :home-fee="homeEdit.home.fee" :last="null" :on-submit="changeHome" @close="homeEdit = null" />
    <ConfirmDialog
      v-if="homeCancel"
      :title="tr('No sample from home?', 'বাসা থেকে নমুনা লাগবে না?')"
      :text="tr('The tests stay with this lab — go there to give the sample. The lab will be told.', 'পরীক্ষা এই প্যাথলজিতেই থাকবে — সেখানে গিয়ে নমুনা দেবেন। প্যাথলজিকে জানানো হবে।')"
      :confirm="tr('I\'ll go to the lab', 'প্যাথলজিতে গিয়ে দেব')"
      :busy="homeBusy"
      :error="homeErr"
      @close="!homeBusy && (homeCancel = null)"
      @confirm="cancelHome"
    />
    <LabPickDialog v-if="pickLab" :rx="rx" @close="pickLab = false" @sent="onLabSent" />
    <LabReportDialog v-if="report" :id="report" @close="report = null" />
    <PayDialog v-if="paying" :r="paying" @close="paying = null" @done="onPaid" />
  </template>
</template>
