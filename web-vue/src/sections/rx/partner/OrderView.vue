<script setup lang="ts">
// প্যাথলজি — একটা পরীক্ষার অনুরোধ: অবস্থা বদল, বাসা থেকে নমুনা (ঠিকানা, ফোন, চাওয়া সময় → সময় নিশ্চিত / যেতে পারব না),
// পাঠানোর সময়ের আনুমানিক দাম, রিপোর্ট দেওয়া, দেওয়া হলে রিপোর্ট দেখা ও ছাপা (অ্যাপের LabReportEntryDialog, LabReportView; প্যানেলের reportForm)
import { computed, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { useNav } from '@/lib/nav';
import { Button, ConfirmDialog, Dialog, ErrorText, Icon, IconLine, RowMenu, Tag, TextInput, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { formatDateTime } from '@/lib/format';
import { homeFeeText, visitText, wantedText } from '@/lib/labprice';
import Panel from './Panel.vue';
import { useInvalidate } from './common';
import { ageText, genderText, labStatusText, labTone, type LabOrder } from './model';
import { LAB_BASE, homeStatusLab } from './labdetail';
import PriceLine from './PriceLine.vue';
import HomeConfirmDialog from './HomeConfirmDialog.vue';
import ReportEntry from './ReportEntry.vue';
import ReportPane from './ReportPane.vue';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';
import { T } from '../../invoices/text';
import { invFeature } from '../../invoices/features';
import InvoiceByIdDialog from '../../invoices/InvoiceByIdDialog.vue';
import AskPay from '../../pay/AskPay.vue';
import { payOn } from '../../pay/features';
import { useSession } from '@/lib/session';

const props = defineProps<{ o: LabOrder; inPane?: boolean }>();
const nav = useNav();
const invalidate = useInvalidate();
const busy = ref(false);
const err = ref<string | null>(null);
const cancelling = ref(false);
const confirming = ref(false);
const declining = ref(false);
const declineNote = ref('');
const receiptId = ref<string | null>(null);
// পরীক্ষার টাকা আগাম (pay_lab): বাসা থেকে নমুনা নিশ্চিত হলে বা বিল হলে
const s = useSession();
const labId = computed(() => String((s.work.lab as { id?: string } | null)?.id ?? s.uid ?? ''));
const askLab = computed(() => payOn('pay_lab') && props.o.status !== 'cancelled' && (!!props.o.invoiceId || props.o.home?.status === 'confirmed'));
const active = computed(() => props.o.status === 'sent' || props.o.status === 'received');
const h = computed(() => props.o.home);
const homeOpen = computed(() => !!h.value && props.o.status === 'sent' && h.value.status !== 'declined');

async function decline() {
  busy.value = true;
  err.value = null;
  try {
    await call('lab_home_decline', { id: props.o.id, note: declineNote.value.trim() });
    await invalidate('lab_orders');
    declining.value = false;
    toast(tr('The patient was told', 'রোগীকে জানানো হয়েছে'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function setStatus(status: 'received' | 'cancelled') {
  busy.value = true;
  err.value = null;
  try {
    await call('lab_order_status', { id: props.o.id, status });
    await invalidate('lab_orders');
    cancelling.value = false;
    toast(status === 'cancelled' ? tr('The patient was told', 'রোগীকে জানানো হয়েছে') : tr('Saved', 'সেভ হয়েছে'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function confirmed() {
  confirming.value = false;
  await invalidate('lab_orders');
  toast(tr('The patient was told the time', 'রোগীকে সময় জানানো হয়েছে'));
}
function askDecline() {
  declineNote.value = '';
  declining.value = true;
}
// একটাই মূল বোতাম (আমার কাজ CONTRACT §২): নমুনা নিয়েছি, নইলে রসিদ দেখুন / বিল করুন; "করতে পারব না" আর বাকিটা "আরও"-তে
const billLink = computed(() => `${LAB_BASE}/bill?order=${encodeURIComponent(props.o.id)}`);
const canBill = computed(() => invFeature('inv_lab') && !props.o.invoiceId && props.o.status !== 'cancelled');
const seeReceipt = computed(() => invFeature('inv_lab') && !!props.o.invoiceId);
const primary = computed<'sample' | 'receipt' | 'bill' | null>(() => (props.o.status === 'sent' ? 'sample' : seeReceipt.value ? 'receipt' : canBill.value ? 'bill' : null));
const moreItems = computed(() => [
  primary.value !== 'receipt' && seeReceipt.value && { label: T.seeReceipt(), icon: 'list_alt', testid: 'lab-see-receipt', onClick: () => (receiptId.value = props.o.invoiceId ?? null) },
  primary.value !== 'bill' && canBill.value && { label: T.billFromOrder(), icon: 'edit_note', testid: 'lab-make-bill', onClick: () => nav(billLink.value) },
  active.value && { label: tr('Can\'t do it', 'করতে পারব না'), icon: 'close', danger: true, disabled: busy.value, testid: 'lab-cant', onClick: () => (cancelling.value = true) },
]);
const homeTone = computed(() => (h.value?.status === 'confirmed' ? 'good' : h.value?.status === 'declined' || h.value?.status === 'expired' ? undefined : 'primary'));
</script>

<template>
  <div class="col gap-16" data-testid="lab-detail">
    <div v-if="!inPane" class="row">
      <RouterLink :to="LAB_BASE" class="btn text small has-icon rxp-back"><Icon name="arrow_back" />{{ tr('All requests', 'সব অনুরোধ') }}</RouterLink>
    </div>
    <section class="card rxp-panel">
      <div class="rxp-rx-head">
        <div class="col gap-4 grow">
          <div class="row wrap gap-8">
            <span class="t-title-l">{{ o.patientName || '—' }}</span>
            <Tag :tone="labTone(o.status)">{{ labStatusText(o.status) }}</Tag>
          </div>
          <span class="t-body-m muted">{{ [ageText(o.patientAge), genderText(o.patientGender), o.doctorName].filter(Boolean).join(' · ') }}</span>
          <span class="t-body-s muted">{{ tr('Came ', 'এসেছে ') }}{{ formatDateTime(o.createdAt) }}</span>
        </div>
        <span v-if="o.code" class="rxp-code-chip num">{{ o.code }}</span>
      </div>
      <div class="row wrap gap-6">
        <Tag v-for="t in o.tests" :key="t" tone="primary">{{ t }}</Tag>
      </div>
      <PriceLine v-if="o.prices" :p="o.prices" />
      <ContactActions :message="chatOn()" @message="openChatAt" ctx="lab_order" :ref-id="o.id" :peer-name="o.patientName" :phone="h?.phone || null" :show="active" />
      <div v-if="primary || moreItems.some(Boolean)" class="q-acts">
        <Button v-if="primary === 'sample'" icon="check_circle" :busy="busy" @click="setStatus('received')">{{ tr('Sample taken', 'নমুনা নিয়েছি') }}</Button>
        <Button v-else-if="primary === 'receipt'" variant="outline" icon="list_alt" data-testid="lab-see-receipt" @click="receiptId = o.invoiceId ?? null">{{ T.seeReceipt() }}</Button>
        <RouterLink v-else-if="primary === 'bill'" :to="billLink" class="btn outline has-icon" data-testid="lab-make-bill"><Icon name="edit_note" />{{ T.billFromOrder() }}</RouterLink>
        <span class="grow" />
        <RowMenu :items="moreItems" />
      </div>
      <AskPay v-if="askLab && labId" kind="lab" :biz-id="labId" ref-type="lab_order" :ref-id="o.id" purpose="lab" />
      <ErrorText :text="err" />
    </section>

    <Panel v-if="h && active" :title="tr('Sample from home', 'বাসা থেকে নমুনা')" icon="home_health">
      <template #actions><Tag :tone="homeTone">{{ homeStatusLab(h) }}</Tag></template>
      <IconLine v-if="h.address" icon="location_on">{{ h.address }}</IconLine>
      <IconLine v-if="h.phone" icon="call"><a class="rxp-phone num" :href="`tel:${h.phone}`">{{ h.phone }}</a></IconLine>
      <IconLine icon="schedule">{{ tr('Wants: ', 'চেয়েছেন: ') + wantedText(h) }}</IconLine>
      <IconLine v-if="h.status === 'confirmed'" icon="check_circle">{{ tr('You said: ', 'আপনি জানিয়েছেন: ') + visitText(h) }}</IconLine>
      <IconLine v-if="h.note" icon="info">{{ h.note }}</IconLine>
      <IconLine v-if="h.fee != null" icon="payments">{{ homeFeeText(h.fee) }}</IconLine>
      <div v-if="homeOpen" class="row wrap gap-8">
        <Button icon="check_circle" :disabled="busy" @click="confirming = true">{{ h.status === 'confirmed' ? tr('Change time', 'সময় বদলান') : tr('Confirm time', 'সময় নিশ্চিত করুন') }}</Button>
        <Button variant="danger-text" :disabled="busy" @click="askDecline">{{ tr('Can\'t go to the home', 'বাসায় যেতে পারব না') }}</Button>
      </div>
    </Panel>

    <ReportEntry v-if="active" :key="o.id" :o="o" />
    <ReportPane v-else-if="o.status === 'reported'" :o="o" />

    <InvoiceByIdDialog v-if="receiptId" :id="receiptId" @close="receiptId = null" />
    <HomeConfirmDialog v-if="confirming && h" :id="o.id" :home="h" @close="confirming = false" @done="confirmed" />
    <Dialog v-if="declining" size="small" :title="tr('Can\'t go to the home?', 'বাসায় যেতে পারবেন না?')" @close="() => !busy && (declining = false)">
      <span class="t-body-m muted">{{ tr('The request stays — the patient can come to the lab.', 'অনুরোধ থাকবে — রোগী প্যাথলজিতে এসে নমুনা দেবেন।') }}</span>
      <TextInput v-model="declineNote" :label="tr('Reason (optional)', 'কারণ (ঐচ্ছিক)')" maxlength="120" />
      <ErrorText :text="err" />
      <template #actions>
        <Button variant="text" :disabled="busy" @click="declining = false">{{ tr('Back', 'ফিরে যান') }}</Button>
        <Button variant="danger" :busy="busy" @click="decline">{{ tr('Can\'t go', 'যেতে পারব না') }}</Button>
      </template>
    </Dialog>
    <ConfirmDialog
      v-if="cancelling"
      :title="tr('Can\'t do these tests?', 'পরীক্ষাগুলো করতে পারবেন না?')"
      :text="tr('The patient will be told and can send them to another lab.', 'রোগী জানবেন, অন্য প্যাথলজিতে পাঠাতে পারবেন।')"
      :confirm="tr('Can\'t do it', 'করতে পারব না')"
      danger
      :busy="busy"
      :error="err"
      @confirm="setStatus('cancelled')"
      @close="cancelling = false"
    />
  </div>
</template>
