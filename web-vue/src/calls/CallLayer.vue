<script setup lang="ts">
// পুরো ওয়েবের উপরে: কল আসার ব্যানার (ধরুন/কেটে দিন), "ডাক্তার ভিডিও কলে আছেন" ব্যানার, আর কলের পর্দা।
// লগইন হলে কলের খবর শোনা শুরু (events.ts), লগআউটে বন্ধ।
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Button, Dialog, Icon, Select } from '@/ui/kit';
import { useNow } from '@/ui/composables';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { eventTiming, startCallEvents, stopCallEvents } from './events';
import { TEST_MODE } from '@/lib/testmode';
import { startRing, userActivated, watchActivation } from './ring';
import {
  accept, acceptVideo, attachVideo, callUi, clearApptReady, decline, declineVideo, dismiss, flipCamera, hangUp, holdEnded, joinAppointment,
  listDevices, requestVideo, resetCalls, setAudioOnly, startAudio, timing, toggleCam, toggleMic, useDevice,
} from './state';
import FlagActions from './FlagActions.vue';
import { T, clock, ctxText, endText } from './text';
import type { DeviceLists } from './media';

const s = useSession();
const now = useNow(1000);
// পরীক্ষায় সময় ছোট করা (window.__lfTest.callTiming = { pollMs, backoffMs … })
if (TEST_MODE) {
  const t = (window as any).__lfTest?.callTiming;
  if (t) {
    Object.assign(timing, t);
    Object.assign(eventTiming, t.events ?? {});
  }
}
onMounted(watchActivation);
watch(
  () => (s.ready && !s.guest ? s.uid : null),
  (uid, old) => {
    if (uid) startCallEvents();
    else {
      stopCallEvents();
      if (old) resetCalls();
    }
  },
  { immediate: true },
);
onBeforeUnmount(stopCallEvents);

const showScreen = computed(() => ['outgoing', 'joining', 'active', 'ended'].includes(callUi.phase));
const peerName = computed(() => callUi.peer?.name || (callUi.appt ? (callUi.appt.role === 'patient' ? tr('Doctor', 'ডাক্তার') : tr('Patient', 'রোগী')) : ''));
const initial = computed(() => (peerName.value.trim()[0] ?? '?').toUpperCase());
const showRemote = computed(() => callUi.video && callUi.remoteVideo && !callUi.audioOnly && callUi.peerIn);
const waiting = computed(() => callUi.phase === 'active' && !!callUi.appt && !callUi.peerIn);

const status = computed(() => {
  if (callUi.phase === 'ended') return endText(callUi.endReason) + (callUi.durationSec ? ' · ' + clock(callUi.durationSec) : '');
  if (callUi.asking) return T.permAsk(callUi.video);
  if (callUi.link === 'reconnecting') return T.reconnecting();
  if (callUi.phase === 'outgoing') return callUi.callId ? T.ringing() : T.calling();
  if (callUi.phase === 'joining') return T.connecting();
  if (waiting.value) return callUi.appt!.role === 'patient' ? T.waitDoctor() : T.waitPatient();
  if (callUi.talkingSince) return clock((now.value - callUi.talkingSince) / 1000);
  return T.connecting();
});

const remoteEl = ref<HTMLVideoElement | null>(null);
const localEl = ref<HTMLVideoElement | null>(null);
watch([remoteEl, showRemote], () => nextTick(() => attachVideo('remote', showRemote.value ? remoteEl.value : null)));
watch([localEl, () => callUi.cam, () => callUi.phase], () => nextTick(() => attachVideo('local', callUi.cam ? localEl.value : null)));

// ব্যানারের শব্দ: আগে পাতায় চাপ না পড়লে ব্রাউজার বাজাতে দেয় না — চাপলে তবেই
function soundOn() {
  callUi.ringAudible = startRing('incoming');
}
const canSound = computed(() => !callUi.ringAudible && !userActivated());

// ডিভাইস বদলানো
const devOpen = ref(false);
const devs = ref<DeviceLists | null>(null);
async function openDevices() {
  devOpen.value = true;
  devs.value = await listDevices();
}
const opts = (l: MediaDeviceInfo[], base: string) => l.map((d, i) => ({ value: d.deviceId, label: d.label || `${base} ${num(i + 1)}` }));
const pick = ref<Record<string, string>>({});
function choose(kind: MediaDeviceKind, id: string) {
  pick.value = { ...pick.value, [kind]: id };
  void useDevice(kind, id);
}
async function joinReady() {
  const id = callUi.apptReady?.appointmentId;
  clearApptReady();
  if (id) await joinAppointment({ appointmentId: id, role: 'patient', peerName: '' });
}
</script>

<template>
  <!-- কল আসছে -->
  <div v-if="callUi.phase === 'incoming'" class="call-banner" role="alertdialog" aria-live="assertive" :aria-label="callUi.video ? T.incomingVideo() : T.incoming()" data-testid="call-incoming">
    <div class="who">
      <span class="pulse"><Icon :name="callUi.video ? 'videocam' : 'call'" /></span>
      <span class="col gap-2 grow" :style="{ minWidth: 0 }">
        <span class="name">{{ callUi.peer?.name || '—' }}</span>
        <span class="t-body-s muted">{{ (callUi.video ? T.incomingVideo() : T.incoming()) + (callUi.ctx ? ' · ' + ctxText(callUi.ctx) : '') }}</span>
      </span>
    </div>
    <button v-if="canSound" type="button" class="btn text small has-icon" data-testid="call-sound" @click="soundOn"><Icon name="volume_up" />{{ T.tapSound() }}</button>
    <div class="acts">
      <button type="button" class="btn square has-icon call-decline" data-testid="call-decline" @click="decline"><Icon name="call_end" />{{ T.decline() }}</button>
      <!-- রক্ত/অ্যাম্বুলেন্স/অর্ডারের ভিডিও কল: অডিওতে না ভিডিওতে ধরবেন -->
      <template v-if="callUi.video && callUi.ctx !== 'appointment'">
        <button type="button" class="btn square has-icon call-accept" data-testid="call-accept-audio" @click="accept(false)"><Icon name="call" />{{ T.answerAudio() }}</button>
        <button type="button" class="btn square has-icon call-accept" data-testid="call-accept" @click="accept(true)"><Icon name="videocam" />{{ T.answerVideo() }}</button>
      </template>
      <button v-else type="button" class="btn square has-icon call-accept" data-testid="call-accept" @click="accept()"><Icon :name="callUi.video ? 'videocam' : 'call'" />{{ T.answer() }}</button>
    </div>
  </div>

  <!-- ডাক্তার অনলাইন অ্যাপয়েন্টমেন্টের কলে আছেন -->
  <div v-else-if="callUi.apptReady && callUi.phase === 'idle'" class="call-banner" role="status" data-testid="appt-call-ready">
    <div class="who">
      <span class="pulse"><Icon name="videocam" /></span>
      <span class="name grow">{{ tr('The doctor is in the video call — join', 'ডাক্তার ভিডিও কলে আছেন — যুক্ত হোন') /* bn-ok */ }}</span>
    </div>
    <div class="acts">
      <Button variant="text" @click="clearApptReady">{{ tr('Later', 'পরে') }}</Button>
      <button type="button" class="btn square has-icon call-accept" data-testid="appt-ready-join" @click="joinReady"><Icon name="videocam" />{{ tr('Join', 'যুক্ত হোন') /* bn-ok */ }}</button>
    </div>
  </div>

  <!-- কলের পর্দা -->
  <div v-if="showScreen" class="call-screen" role="dialog" aria-modal="true" :aria-label="peerName || T.video()" data-testid="call-screen" :data-phase="callUi.phase">
    <video v-show="showRemote" ref="remoteEl" class="call-remote" autoplay playsinline data-testid="call-remote-video" />
    <video v-if="callUi.cam && callUi.phase !== 'ended'" ref="localEl" class="call-local" autoplay playsinline muted data-testid="call-local-video" />
    <div class="call-top">
      <span class="name" data-testid="call-peer">{{ peerName }}</span>
      <span class="sub" data-testid="call-status" aria-live="polite">{{ status }}</span>
      <span v-if="callUi.ctx && !waiting" class="sub">{{ (callUi.video ? T.video() : T.audioCall()) + ' · ' + ctxText(callUi.ctx) }}</span>
    </div>
    <div class="call-mid">
      <template v-if="!showRemote">
        <div class="call-face"><img v-if="callUi.peer?.photoThumb" :src="callUi.peer.photoThumb.startsWith('data:') ? callUi.peer.photoThumb : `data:image/jpeg;base64,${callUi.peer.photoThumb}`" alt="" /><span v-else>{{ initial }}</span></div>
        <span v-if="waiting" class="t-title-m">{{ T.waitingRoom() }}</span>
        <span v-if="waiting" class="call-note">{{ T.waitSub() }}</span>
      </template>
      <div v-if="callUi.error" class="call-note err" role="alert" data-testid="call-error">
        <div>{{ callUi.error }}</div>
        <a v-if="callUi.phone" class="btn outline small square has-icon" :href="`tel:${callUi.phone}`"><Icon name="call" />{{ T.phoneInstead() + ' ' + num(callUi.phone) }}</a>
      </div>
      <div v-if="callUi.note && callUi.phase !== 'ended'" class="call-note" role="status" data-testid="call-note">{{ callUi.note }}</div>
      <!-- অডিও কলে ভিডিও: ওপাশ চাইছেন -->
      <div v-if="callUi.upgrade === 'offered' && callUi.phase === 'active'" class="call-note" role="alertdialog" data-testid="call-video-offer">
        <div>{{ T.offeredVideo() }}</div>
        <div class="row gap-8" :style="{ justifyContent: 'center', marginTop: '6px' }">
          <button type="button" class="btn outline small" data-testid="call-video-no" @click="declineVideo">{{ T.notNow() }}</button>
          <button type="button" class="btn small call-accept has-icon" data-testid="call-video-yes" @click="acceptVideo"><Icon name="videocam" />{{ T.agree() }}</button>
        </div>
      </div>
      <button
        v-if="callUi.phase === 'active' && !callUi.video && !callUi.appt && !callUi.upgrade && callUi.peerIn"
        type="button"
        class="btn outline small has-icon"
        data-testid="call-ask-video"
        @click="requestVideo"
      ><Icon name="videocam" />{{ T.askVideo() }}</button>
      <div v-if="callUi.poor && !callUi.audioOnly && callUi.video && callUi.phase === 'active'" class="call-note" data-testid="call-poor">
        <div>{{ T.weak() }}</div>
        <button type="button" class="btn outline small" data-testid="call-audio-only" @click="setAudioOnly(true)">{{ T.audioOnly() }}</button>
      </div>
      <button v-if="callUi.audioBlocked && callUi.phase === 'active'" type="button" class="btn outline small has-icon" data-testid="call-start-audio" @click="startAudio"><Icon name="volume_up" />{{ T.tapSound() }}</button>
    </div>
    <div class="call-bottom">
      <template v-if="callUi.phase === 'ended'">
        <div class="col gap-12" :style="{ alignItems: 'center' }">
          <FlagActions v-if="callUi.canFlag && callUi.peer && callUi.callId" :peer-uid="callUi.peer.uid" :peer-name="callUi.peer.name" :call-id="callUi.callId" dark :small="false" @open="holdEnded" />
          <Button v-if="callUi.error || callUi.canFlag" variant="outline" data-testid="call-close" @click="dismiss">{{ tr('Close', 'বন্ধ') }}</Button>
        </div>
      </template>
      <template v-else>
        <div class="call-ctl-wrap">
          <button type="button" :class="['call-ctl', !callUi.mic && 'off']" :aria-pressed="!callUi.mic" :aria-label="callUi.mic ? T.micOff() : T.micOn()" data-testid="call-mic" :disabled="callUi.asking" @click="toggleMic">
            <Icon :name="callUi.mic ? 'mic' : 'mic_off'" />
          </button>
          <span>{{ callUi.mic ? T.micOff() : T.micOn() }}</span>
        </div>
        <div v-if="callUi.video" class="call-ctl-wrap">
          <button type="button" :class="['call-ctl', !callUi.cam && 'off']" :aria-pressed="!callUi.cam" :aria-label="callUi.cam ? T.camOff() : T.camOn()" data-testid="call-cam" :disabled="callUi.asking" @click="toggleCam">
            <Icon :name="callUi.cam ? 'videocam' : 'videocam_off'" />
          </button>
          <span>{{ callUi.cam ? T.camOff() : T.camOn() }}</span>
        </div>
        <div v-if="callUi.cam" class="call-ctl-wrap">
          <button type="button" class="call-ctl" :aria-label="T.flip()" data-testid="call-flip" @click="flipCamera"><Icon name="cameraswitch" /></button>
          <span>{{ T.flip() }}</span>
        </div>
        <div v-if="callUi.audioOnly" class="call-ctl-wrap">
          <button type="button" class="call-ctl off" :aria-label="T.withVideo()" data-testid="call-video-back" @click="toggleCam"><Icon name="videocam" /></button>
          <span>{{ T.withVideo() }}</span>
        </div>
        <div class="call-ctl-wrap">
          <button type="button" class="call-ctl" :aria-label="T.devices()" data-testid="call-devices" :disabled="callUi.asking" @click="openDevices"><Icon name="settings_outline" /></button>
          <span>{{ T.devices() }}</span>
        </div>
        <div class="call-ctl-wrap">
          <button type="button" class="call-ctl end" :aria-label="callUi.appt ? T.leave() : T.hangUp()" data-testid="call-hangup" @click="hangUp"><Icon name="call_end" /></button>
          <span>{{ callUi.appt ? T.leave() : T.hangUp() }}</span>
        </div>
      </template>
    </div>
    <Dialog v-if="devOpen" :title="T.devices()" size="small" @close="devOpen = false">
      <div class="col gap-12" data-testid="call-device-dialog">
        <Select v-if="devs?.audioinput.length" :aria-label="T.mic()" :model-value="pick.audioinput ?? ''" :options="[{ value: '', label: T.mic() }, ...opts(devs.audioinput, T.mic())]" @update:model-value="(v: string) => v && choose('audioinput', v)" />
        <Select v-if="callUi.video && devs?.videoinput.length" :aria-label="T.camera()" :model-value="pick.videoinput ?? ''" :options="[{ value: '', label: T.camera() }, ...opts(devs.videoinput, T.camera())]" @update:model-value="(v: string) => v && choose('videoinput', v)" />
        <Select v-if="devs?.audiooutput.length" :aria-label="T.speaker()" :model-value="pick.audiooutput ?? ''" :options="[{ value: '', label: T.speaker() }, ...opts(devs.audiooutput, T.speaker())]" @update:model-value="(v: string) => v && choose('audiooutput', v)" />
        <Button v-if="callUi.video && !callUi.audioOnly" variant="outline" icon="call" @click="setAudioOnly(true); devOpen = false">{{ T.audioOnly() }}</Button>
      </div>
    </Dialog>
  </div>
</template>
