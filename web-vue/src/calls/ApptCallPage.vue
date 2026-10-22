<script setup lang="ts">
// অনলাইন (ভিডিও) অ্যাপয়েন্টমেন্টের কলে ঢোকার পাতা: /appointments/:id/call[?role=provider&name=…]
// "ভিডিও কলে যুক্ত হোন" → অনুমতি → appt_join → অপেক্ষার ঘর (অন্যজন এলে কথা) — কলের পর্দা CallLayer দেখায়।
// সময়ের আগে/পরে সার্ভার "না" বললে তার লেখাই দেখায়। লিংক দেয় অ্যাপয়েন্টমেন্টের পাতা (রোগী) আর চেম্বারের বুকিং (ডাক্তার/সহকারী)।
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { Button, EmptyState, ErrorText, Icon, InfoText } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { matchPath } from '@/lib/nav';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { callUi, dismiss, joinAppointment } from './state';
import { mediaSupported } from './media';
import { T } from './text';

const route = useRoute();
const s = useSession();
const id = computed(() => decodeURIComponent(matchPath('/appointments/:id/call', route.path)?.params.id ?? ''));
const role = computed<'patient' | 'provider'>(() => (route.query.role === 'provider' ? 'provider' : 'patient'));
const peerName = computed(() => (typeof route.query.name === 'string' ? route.query.name : ''));
const mine = computed(() => callUi.appt?.id === id.value);
const err = computed(() => (callUi.phase === 'ended' && callUi.ctx === 'appointment' && callUi.refId === id.value ? callUi.error : null));
const back = computed(() => (role.value === 'provider' ? '/work/pro' : '/my-appointments'));
const title = () => tr('Video appointment', 'ভিডিও অ্যাপয়েন্টমেন্ট'); // bn-ok

function join() {
  if (callUi.phase === 'ended') dismiss();
  s.requireLogin(tr('Log in to do this', 'এর জন্য লগইন করুন'), () => void joinAppointment({ appointmentId: id.value, role: role.value, peerName: peerName.value }));
}
</script>

<template>
  <FormPage :title="title()" :back-to="back">
    <div class="col gap-16 call-lobby" data-testid="appt-call-page">
      <div class="call-lobby-ic"><Icon name="videocam" :size="40" /></div>
      <h2 class="t-title-l">{{ peerName || title() }}</h2>
      <p class="t-body-m muted">
        {{ role === 'provider'
          ? tr('Join when you are ready. The patient is told that you are in the call.', 'প্রস্তুত হলে যুক্ত হোন — রোগীকে জানানো হবে যে আপনি কলে আছেন।') /* bn-ok */
          : tr('You can join from 15 minutes before your time. Wait in the call — the doctor joins when it is your turn.', 'সময়ের ১৫ মিনিট আগে থেকে যুক্ত হতে পারবেন। কলে অপেক্ষা করুন — আপনার পালা এলে ডাক্তার যুক্ত হবেন।') /* bn-ok */ }}
      </p>
      <EmptyState v-if="!mediaSupported()" icon="warning" :title="T.noSupport()" />
      <template v-else>
        <InfoText :text="T.permAsk(true)" />
        <ErrorText :text="err" />
        <Button block icon="videocam" :busy="mine && (callUi.phase === 'joining' || callUi.phase === 'active')" data-testid="appt-join" @click="join">
          {{ tr('Join the video call', 'ভিডিও কলে যুক্ত হোন') /* bn-ok */ }}
        </Button>
      </template>
    </div>
  </FormPage>
</template>
