<script setup lang="ts">
// /work/lab/info (অনুমোদনের আগে /work/lab) — নিজের প্যাথলজি: অবস্থা, কাগজ, বদল, একদম নিচে মুছে ফেলা। ২.২৭.০ থেকে "ল্যাব" ট্যাব;
// পরীক্ষার অনুরোধ "অনুরোধ" ট্যাবে (/work/lab/orders) — অ্যাপের MyLabPanel (MyWorkTabs.kt), LabEditScreen
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, Icon } from '@/ui/kit';
import { useSession } from '@/lib/session';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { useNav } from '@/lib/nav';
import { labDocs, myBusinessTitle, statusText, useAfterChange, useAreas, useFlash, type Lab } from './shared';
import BizPage from './BizPage.vue';
import WorkGate from './WorkGate.vue';
import WorkEmpty from './WorkEmpty.vue';
import Flash from './Flash.vue';
import StatusTag from './StatusTag.vue';
import StatusReason from './StatusReason.vue';
import AppealButton from './AppealButton.vue';
import DocsNeededNotice from './DocsNeededNotice.vue';
import LabTabs from './LabTabs.vue';
import DangerZone from './DangerZone.vue';

const s = useSession();
const nav = useNav();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const lab = computed(() => s.work.lab as Lab | null);
const after = useAfterChange();
const [flash, setFlash] = useFlash();
const del = ref(false);
const busy = ref(false);
const delErr = ref<string | null>(null);
useAreas();
const title = computed(() => myBusinessTitle('lab'));
async function remove() {
  busy.value = true;
  delErr.value = null;
  try {
    await call('lab_delete');
    await after();
    del.value = false;
    setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
  } catch (e) {
    delErr.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
const place = computed(() => (lab.value ? [lab.value.address, areaLabel(lab.value.district, lab.value.upazilaId)].filter(Boolean).join(', ') : ''));
const askDel = () => {
  delErr.value = null;
  del.value = true;
};
</script>

<template>
  <BizPage v-if="!ready" :title="title"><WorkGate /></BizPage>
  <BizPage v-else-if="!lab" :title="title">
    <Flash :msg="flash" />
    <WorkEmpty
      icon="local_hospital"
      :title="tr('Run a diagnostic centre?', 'ডায়াগনস্টিক সেন্টার চালান?')"
      :text="tr('Join so patients can send you tests from their prescriptions, and give reports in the app. Free.', 'যোগ দিন — রোগীরা প্রেসক্রিপশন থেকে আপনার কাছে পরীক্ষা পাঠাবেন, রিপোর্ট অ্যাপেই দেবেন। বিনামূল্যে।')"
    >
      <Button icon="add" @click="nav('/work/lab/new')">{{ tr('Add my lab', 'আমার প্যাথলজি যোগ করুন') }}</Button>
    </WorkEmpty>
  </BizPage>
  <BizPage v-else :title="title">
    <template #actions><Button variant="tonal" icon="edit" @click="nav('/work/lab/edit')">{{ tr('Edit details', 'তথ্য বদলান') }}</Button></template>
    <LabTabs active="info" />
    <Flash :msg="flash" />
    <DocsNeededNotice kind="lab" :id="lab.id" :specs="labDocs()" :item="lab" @done="after" />
    <div class="ws-grid side">
      <div class="col gap-16">
        <section v-if="lab.status === 'approved'" class="card col gap-8">
          <h3 class="t-title-m">{{ tr('Tests & reports', 'পরীক্ষা ও রিপোর্ট') }}</h3>
          <p class="t-body-s muted">{{ tr('Set your test prices, and your machine\'s normal ranges for reports.', 'পরীক্ষার দাম আর রিপোর্টের জন্য আপনার মেশিনের স্বাভাবিক মাত্রা বসান।') }}</p>
          <div class="row wrap gap-8">
            <Button variant="tonal" icon="payments" @click="nav('/work/lab/orders/prices')">{{ tr('Tests & prices', 'পরীক্ষা ও দাম') }}</Button>
            <Button variant="tonal" icon="tune" @click="nav('/work/lab/orders/ranges')">{{ tr('Normal ranges', 'স্বাভাবিক মাত্রা') }}</Button>
          </div>
        </section>
        <section v-else class="card work-partner">
          <span class="work-ic"><Icon name="schedule" /></span>
          <div class="col gap-2 grow">
            <h3 class="t-title-m">{{ statusText(lab.status) }}</h3>
            <span v-if="lab.status === 'pending'" class="t-body-s muted">{{ tr('You\'ll appear in the list after approval.', 'অনুমোদনের পর তালিকায় দেখাবে।') }}</span>
            <StatusReason :status="lab.status" :reason="lab.statusReason" />
          </div>
          <AppealButton v-if="lab.status === 'rejected' || lab.status === 'suspended'" />
        </section>
      </div>
      <aside class="card col gap-8 work-side">
        <div class="row top">
          <span class="work-ic"><Icon name="local_hospital" /></span>
          <div class="col gap-4 grow">
            <h3 class="t-title-m">{{ lab.name }}</h3>
          </div>
          <StatusTag :status="lab.status" />
        </div>
        <span class="icon-line"><Icon name="call" /><span class="num">{{ num(lab.phone) }}</span></span>
        <span v-if="place" class="icon-line"><Icon name="location_on" /><span>{{ place }}</span></span>
        <span v-if="lab.hours" class="icon-line"><Icon name="schedule" /><span>{{ lab.hours }}</span></span>
        <span v-if="lab.homeCollection" class="icon-line"><Icon name="home_health" /><span>{{ tr('Collects samples from home', 'বাসা থেকে নমুনা নেয়') }}</span></span>
        <span class="icon-line"><Icon name="badge" /><span>{{ tr('DGHS licence number', 'স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর') }}: {{ lab.licenseNo }}</span></span>
      </aside>
    </div>
    <DangerZone v-if="lab.status !== 'suspended'">
      <Button variant="danger-text" icon="delete" :disabled="busy" @click="askDel">{{ tr('Remove my lab', 'প্যাথলজি মুছে ফেলুন') }}</Button>
    </DangerZone>
    <ConfirmDialog
      v-if="del"
      :title="tr('Remove your lab?', 'প্যাথলজি মুছে ফেলবেন?')"
      :text="tr('It will disappear from the list. You can add it again later (it will need approval again).', 'তালিকা থেকে সরে যাবে। পরে আবার যোগ করা যাবে (আবার অনুমোদন লাগবে)।')"
      :confirm="tr('Remove', 'মুছে ফেলুন')"
      danger
      :busy="busy"
      :error="delErr"
      @confirm="remove"
      @close="del = false"
    />
  </BizPage>
</template>
