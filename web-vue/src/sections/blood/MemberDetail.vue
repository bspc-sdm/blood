<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Button, ConfirmDialog, ErrorText, IconButton, InfoText, Loading } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { addCommitment, isDonor } from '@/lib/profile';
import { canDonate, notEligibleReason } from '@/lib/rules';
import { distanceKm } from '@/lib/geo';
import { TEST_MODE } from '@/lib/testmode';
import { useNav } from '@/lib/nav';
import { useCall, useQ } from '@/lib/query';
import { KEY, api, dropCommitments, getRequest, myResponse, reportRequest, requesterContact, useInvalidate } from './data';
import { isActive, type BloodRequest, type DonorResponse } from './model';
import RequestInfo from './parts/RequestInfo.vue';
import ProcessingCard from './parts/ProcessingCard.vue';
import StatusCard from './parts/StatusCard.vue';
import Note from './parts/Note.vue';
import Disclaimer from './parts/Disclaimer.vue';
import ShareDialog from './parts/ShareDialog.vue';
import ShareCard from './ShareCard.vue';
import DonationChecklistDialog from './DonationChecklistDialog.vue';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';

const props = defineProps<{ id: string; title: string }>();
const s = useSession();
const nav = useNav();
const invalidate = useInvalidate();
const uid = s.uid!;
const busy = ref(false);
const error = ref<string | null>(null);
const info = ref<string | null>(null);
const checklist = ref(false);
const reporting = ref(false);
const sharing = ref(false);

const q = useQ<{ r: BloodRequest | null; resp: DonorResponse | null; contact: { name: string; phone: string } | null }>(() => ({
  queryKey: [KEY, 'req', props.id, uid],
  queryFn: async () => {
    const r = await getRequest(props.id);
    const resp = r && r.requesterUid !== uid ? await myResponse(props.id, uid) : null;
    // নম্বর শুধু রাজি থাকা বা রক্ত দেওয়া ডোনার পড়তে পারেন, আর রিকোয়েস্ট খোলা থাকলে
    const canSee = !!r && r.status === 'open' && (resp?.status === 'accepted' || resp?.status === 'donated');
    const contact = canSee ? await requesterContact(props.id).catch(() => null) : null;
    return { r, resp, contact };
  },
  staleTime: 5_000,
}));
const r = computed(() => q.data?.r ?? null);
const resp = computed(() => q.data?.resp ?? null);
// ডোনার → রক্তপ্রার্থী: সার্ভারের contact_can; তা না পেলে (পুরোনো সার্ভার) — নিশ্চিত হলে, বা রক্তপ্রার্থী আগে এই রিকোয়েস্টে কল করে থাকলে
const callHist = useCall<{ items?: any[] }>('call_history', {}, () => ({
  enabled: resp.value?.status === 'accepted' && !resp.value.confirmed,
  staleTime: 60_000,
  retry: false,
}));
const requesterCalled = computed(() => (callHist.data?.items ?? []).some((x) => x?.ctx === 'blood' && x?.refId === props.id && x?.dir === 'in'));

// আগে রাজি হওয়া রিকোয়েস্টও প্রসেসিং তালিকায় তোলা
const accepted = computed(() => !!r.value && resp.value?.status === 'accepted' && isActive(r.value));
watch(
  accepted,
  (a) => {
    const profile = s.profile;
    if (!a || !profile || profile.commitments.includes(props.id) || TEST_MODE) return;
    addCommitment(uid, props.id).then(() => s.reloadProfile()).catch(() => {});
  },
  { immediate: true },
);

async function act(f: () => Promise<unknown>, msg?: string) {
  busy.value = true;
  error.value = null;
  try {
    await f();
    if (msg) info.value = msg;
    await invalidate();
  } catch (e) {
    error.value = errorText(e) || tr('Something went wrong', 'কিছু একটা সমস্যা হয়েছে');
  } finally {
    busy.value = false;
  }
}
const donated = () =>
  act(async () => {
    await api.updateResponse(props.id, 'donated');
    // তারিখ সার্ভার বসায় (বাংলাদেশের দিন ধরে)
    await s.reloadProfile();
  });
const cancel = () =>
  act(async () => {
    await api.updateResponse(props.id, 'cancel');
    await dropCommitments(uid, [props.id]).catch(() => {});
    await s.reloadProfile();
  }, tr('Cancelled. The requester can no longer see your number.', 'বাতিল করা হয়েছে। রক্তপ্রার্থী এখন আর আপনার নম্বর দেখবেন না।'));

const mine = computed(() => !!r.value && r.value.requesterUid === uid);
const active = computed(() => !!r.value && isActive(r.value));
const km = computed(() => {
  const profile = s.profile;
  const x = r.value;
  return x && profile?.lat != null && profile?.lng != null && x.lat ? distanceKm(profile.lat, profile.lng, x.lat, x.lng) : null;
});
const group = computed(() => s.profile?.bloodGroup ?? '');
const compatible = computed(() => !!r.value && canDonate(group.value, r.value.patientGroup));
const notEligible = computed(() => (s.profile ? notEligibleReason(s.profile) : null));
const eligible = computed(() => notEligible.value == null);
const donorOk = computed(() => isDonor(s.profile));
const accState = computed<[string, string]>(() =>
  resp.value?.confirmed
    ? ['check_circle', tr('The requester confirmed you. Please go to the hospital on time.', 'রক্তপ্রার্থী আপনাকে নিশ্চিত করেছেন। সময়মতো হাসপাতালে যান।')]
    : r.value?.fulfilled
      ? ['info', tr("Enough donors are confirmed for now. Thank you! You'll be told if you're needed.", 'আপাতত যথেষ্ট ডোনার নিশ্চিত হয়েছেন। ধন্যবাদ! দরকার হলে জানানো হবে।')]
      : ['schedule', tr('Waiting for the requester to confirm you.', 'রক্তপ্রার্থী আপনাকে নিশ্চিত করার অপেক্ষায়।')],
);
const askDonor = () => s.requireLogin(tr('Add your donor details to respond', 'সাড়া দিতে রক্তদাতার তথ্য দিন'), () => {}, { donor: true });
function confirmChecklist() {
  checklist.value = false;
  act(async () => {
    await api.respond(props.id);
    await s.reloadProfile();
  });
}
function doReport() {
  reporting.value = false;
  reportRequest(props.id, uid).then(
    () => (info.value = tr('Reported. Thank you.', 'রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।')),
    () => (error.value = tr("Couldn't send the report", 'রিপোর্ট পাঠানো যায়নি')),
  );
}
</script>

<template>
  <DetailPane :title="title" back-to="/">
    <template v-if="r" #actions>
      <IconButton v-if="active" icon="share" :label="tr('Share', 'শেয়ার')" @click="sharing = true" />
      <IconButton v-if="!mine" icon="flag" :label="tr('Report', 'রিপোর্ট')" @click="reporting = true" />
    </template>
    <Loading v-if="q.isLoading" />
    <ErrorText v-else-if="!r" :text="q.error ? tr('Couldn\'t load', 'লোড হয়নি') : tr('Request not found', 'রিকোয়েস্টটা পাওয়া যায়নি')" />
    <template v-else>
      <ProcessingCard v-if="resp?.status === 'accepted' && active" :r="r" />
      <RequestInfo :r="r" :km="km" />
      <ShareCard v-if="active && !mine" :r="r" @share="sharing = true" />
      <!-- মূল কাজ -->
      <Button v-if="mine" block @click="nav(`/my-requests/${r.id}`)">{{ tr('View responses', 'সাড়া দেওয়া ডোনারদের দেখুন') }}</Button>
      <template v-else-if="!resp && !active" />
      <template v-else-if="!resp && !donorOk">
        <!-- ডোনারের তথ্য নেই: আগে সেটা চেয়ে নেওয়া -->
        <Button block icon="volunteer_activism" @click="askDonor">{{ tr('I want to donate', 'রক্ত দিতে চাই') }}</Button>
        <Note>{{ tr('You can still share this request.', 'চাইলে রিকোয়েস্টটা পরিচিতদের পাঠাতে পারেন।') }}</Note>
      </template>
      <Note v-else-if="!resp && !compatible">
        {{ tr(
          `Your group (${group}) can't donate to ${r.patientGroup}. You can share it with others.`,
          `আপনার গ্রুপ (${group}) থেকে ${r.patientGroup} রোগীকে রক্ত দেওয়া যায় না। পরিচিতদের জানাতে পারেন।`,
        ) }}
      </Note>
      <Note v-else-if="!resp && !eligible">{{ notEligible }}</Note>
      <template v-else-if="!resp">
        <Button block icon="volunteer_activism" :busy="busy" @click="checklist = true">{{ tr("I'll donate", 'রক্ত দেব') }}</Button>
        <Note>{{ tr('Your name and number will be shared with the requester.', 'রক্তপ্রার্থী আপনার নাম ও নম্বর দেখতে পাবেন।') }}</Note>
      </template>
      <template v-else-if="resp.status === 'accepted' && !active">
        <!-- রিকোয়েস্ট বন্ধ/মেয়াদ শেষ: আর যাওয়ার দরকার নেই; রক্ত দিয়ে থাকলে জানানো -->
        <StatusCard icon="info">
          {{ tr("This request is closed — you don't need to go. If you already donated, tell us below.", 'রিকোয়েস্টটা বন্ধ হয়ে গেছে, আর যেতে হবে না। আগেই রক্ত দিয়ে থাকলে নিচে জানান।') }}
        </StatusCard>
        <Button variant="outline" block icon="check_circle" :busy="busy" @click="donated">{{ tr('I donated for this', 'এতে রক্ত দিয়েছি') }}</Button>
      </template>
      <template v-else-if="resp.status === 'accepted'">
        <StatusCard :icon="accState[0]" :tone="resp.confirmed ? 'good' : 'high'">{{ accState[1] }}</StatusCard>
        <section class="card blood-contact col">
          <h3 class="bold">{{ tr('Contact the requester', 'রক্তপ্রার্থীর সাথে যোগাযোগ করুন') }}</h3>
          <template v-if="q.data?.contact">
            <span>{{ q.data.contact.name }}</span>
            <ContactActions :message="chatOn()" @message="openChatAt" ctx="blood" :ref-id="id" :peer-uid="r.requesterUid" :peer-name="q.data.contact.name" :phone="q.data.contact.phone" :small="false" :show="resp.confirmed || requesterCalled" show-number />
          </template>
          <span v-else>{{ tr("Couldn't load the number. Open this page again.", 'নম্বর লোড হয়নি, পাতাটা আবার খুলুন।') }}</span>
        </section>
        <Button block icon="check_circle" :busy="busy" @click="donated">{{ tr('I donated', 'রক্ত দিয়েছি') }}</Button>
        <Button variant="text" block :disabled="busy" @click="cancel">{{ tr("I can't make it, cancel", 'আর পারছি না, বাতিল করুন') }}</Button>
      </template>
      <StatusCard v-else-if="resp.status === 'donated'" icon="favorite" tone="good">{{ tr('Thank you! You helped save a life.', 'ধন্যবাদ! আপনি একজনের জীবন বাঁচাতে সাহায্য করেছেন।') }}</StatusCard>
      <Note v-else-if="resp.status === 'declined'">{{ tr("The requester no longer needs you for this request — you don't need to go. Thank you!", 'রক্তপ্রার্থী এই রিকোয়েস্টে আপনাকে আর রাখেননি, আপনাকে যেতে হবে না। ধন্যবাদ!') }}</Note>
      <template v-else>
        <Note>{{ tr('You cancelled for this request.', 'আপনি এই রিকোয়েস্টে রক্ত দেওয়া বাতিল করেছেন।') }}</Note>
        <Button v-if="active && compatible && eligible" variant="outline" block :disabled="busy" @click="checklist = true">{{ tr('I can donate after all', 'আবার রক্ত দিতে চাই') }}</Button>
      </template>
      <ErrorText :text="error" />
      <InfoText :text="info" />
      <Disclaimer />
    </template>
    <DonationChecklistDialog v-if="checklist" @dismiss="checklist = false" @confirm="confirmChecklist" />
    <ConfirmDialog
      v-if="reporting"
      :title="tr('Report this request?', 'রিপোর্ট করবেন?')"
      :text="tr('Report it if it looks fake or abusive.', 'ভুয়া বা অপব্যবহার মনে হলে রিপোর্ট করুন।')"
      :confirm="tr('Report', 'রিপোর্ট করুন')"
      @close="reporting = false"
      @confirm="doReport"
    />
    <ShareDialog v-if="sharing && r" :r="r" @close="sharing = false" />
  </DetailPane>
</template>
