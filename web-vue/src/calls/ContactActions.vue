<script setup lang="ts">
// যোগাযোগের বোতাম (প্রতিটা যোগাযোগের জায়গায় একই): [ফোন] · অডিও কল · ভিডিও কল · মেসেজ।
// - ফোন: শুধু `with-phone` দিলে (বেশিরভাগ পাতায় ফোনের নিজের বোতাম আগে থেকেই আছে — জরুরি পাতায় ফোনই প্রথম)।
// - অডিও/ভিডিও কল: সার্ভারের contact_can (canCall/canVideo); তা না পেলে পাতার নিজের হিসাব (`show`) আর call_config।
// - মেসেজ: `message` দিলে তবেই, আর canMessage হলে; চাপলে `message` ঘটনা {ctx, refId, peerUid} (চ্যাট অংশ খোলে)।
// - blocked (আমি ব্লক করেছি): বোতামের বদলে "আনব্লক"।
// দেখায় শুধু লগইন থাকলে, নিজের হয়ে (কেয়ারগিভার মোডে নয়)।
import { computed, ref } from 'vue';
import { Icon, toast } from '@/ui/kit';
import { cx } from '@/ui/composables';
import { errorText } from '@/lib/api';
import { num } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useCall } from '@/lib/query';
import { callUnblock, type CallCtx } from './api';
import { contactCan, forgetContactCan } from './contactCan';
import { busy, startCall } from './state';
import { T } from './text';

const props = withDefaults(
  defineProps<{
    ctx: CallCtx;
    refId: string;
    peerUid?: string;
    peerName?: string;
    phone?: string | null;
    /** পাতার নিজের হিসাব (সার্ভারের উত্তর না পেলে) */
    show?: boolean;
    small?: boolean;
    /** ফোনের বোতামও এখানেই */
    withPhone?: boolean;
    /** মেসেজ বোতাম (চ্যাট অংশ জুড়লে) */
    message?: boolean;
  }>(),
  { show: true, small: true, peerUid: undefined, peerName: '', phone: null, withPhone: false, message: false },
);
const emit = defineEmits<{ message: [target: { ctx: CallCtx; refId: string; peerUid?: string }] }>();
const s = useSession();
const care = useCareMode();
const me = computed(() => !!s.uid && !s.guest && !care.value && !!props.refId);
const can = computed(() => (me.value ? contactCan({ ctx: props.ctx, refId: props.refId, peerUid: props.peerUid }) : null));
// সার্ভারে contact_can না থাকলে: call_config + পাতার হিসাব
const cfg = useCall<{ enabled?: boolean }>('call_config', {}, () => ({ enabled: me.value && can.value === null, staleTime: 10 * 60_000, retry: false }));
const fallback = computed(() => props.show && cfg.data?.enabled === true);
const audio = computed(() => me.value && (can.value ? can.value.canCall && !can.value.blocked : can.value === null && fallback.value));
const video = computed(() => me.value && (can.value ? can.value.canVideo && !can.value.blocked : can.value === null && fallback.value));
const msg = computed(() => me.value && props.message && !!can.value?.canMessage && !can.value.blocked);
const blocked = computed(() => me.value && !!can.value?.blocked);
const phoneShown = computed(() => props.withPhone && !!props.phone);
const any = computed(() => phoneShown.value || audio.value || video.value || msg.value || blocked.value);

const go = (v: boolean) => startCall({ ctx: props.ctx, refId: props.refId, peerUid: props.peerUid, video: v, peerName: props.peerName, phone: props.phone });
const unblocking = ref(false);
async function unblock() {
  if (!props.peerUid) return;
  unblocking.value = true;
  try {
    await callUnblock(props.peerUid);
    forgetContactCan();
    toast(T.unblocked());
  } catch (e) {
    toast(errorText(e));
  } finally {
    unblocking.value = false;
  }
}
const btn = computed(() => cx('btn outline square has-icon', props.small && 'small'));
</script>

<template>
  <div v-if="any" class="row wrap gap-8 call-btns" data-testid="call-buttons" @click.stop>
    <a v-if="phoneShown" :class="cx('btn square has-icon', small && 'small')" :href="`tel:${phone}`" data-testid="contact-phone"><Icon name="call" />{{ T.phone() + ' ' + num(phone!) }}</a>
    <template v-if="blocked">
      <span class="t-body-s muted">{{ T.blockedShort() }}</span>
      <button type="button" :class="btn" :disabled="unblocking || !peerUid" data-testid="contact-unblock" @click="unblock">{{ T.unblock() }}</button>
    </template>
    <template v-else>
      <button v-if="audio" type="button" :class="btn" :disabled="busy()" data-testid="call-audio" @click="go(false)">
        <Icon name="call" />{{ T.audioCall() }}
      </button>
      <button v-if="video" type="button" :class="btn" :disabled="busy()" data-testid="call-video" @click="go(true)">
        <Icon name="videocam" />{{ T.video() }}
      </button>
      <button v-if="msg" type="button" :class="btn" data-testid="contact-message" @click="emit('message', { ctx, refId, peerUid })">
        <Icon name="forum_outline" />{{ T.message() }}
      </button>
    </template>
  </div>
</template>
