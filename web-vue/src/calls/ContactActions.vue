<script setup lang="ts">
// যোগাযোগ (প্রতিটা যোগাযোগের জায়গায় একই, মালিকের সিদ্ধান্ত RC4): শুধু দুটো আইকন — কল · মেসেজ।
// - কলের আইকন: ছোট মেনু — "ফোন কল" (tel:) · "অ্যাপ কল" (অ্যাপে অডিও) · "ভিডিও কল"; শুধু যেগুলো চলে।
//   একটাই চললে মেনু ছাড়া সরাসরি সেটা; একটাও না চললে আইকন নেই।
// - ফোন কল: `phone` দিলে (with-phone=false না হলে) — লগইন/কেয়ারগিভার মোড যা-ই হোক। চাপলে `phone` ঘটনাও (যেমন অ্যাম্বুলেন্সে গোনা)।
// - অ্যাপ/ভিডিও কল: সার্ভারের contact_can (canCall/canVideo); তা না পেলে পাতার নিজের হিসাব (`show`) আর call_config।
// - মেসেজ: `message` দিলে তবেই, আর canMessage হলে; চাপলে `message` ঘটনা {ctx, refId, peerUid} (চ্যাট অংশ খোলে)।
// - blocked (আমি ব্লক করেছি): অ্যাপের কল/মেসেজের বদলে "আনব্লক" (ফোন কল থাকে)।
// - show-number: আইকনের আগে নম্বরটা লেখা (বেছে কপি করা যায়) — পাতায় নম্বর আর কোথাও না থাকলে।
// অ্যাপের কল/মেসেজ শুধু লগইন থাকলে, নিজের হয়ে (কেয়ারগিভার মোডে নয়)। ফিচার সুইচ: calls_audio, calls_video, chat।
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { Icon, toast } from '@/ui/kit';
import { cx } from '@/ui/composables';
import { errorText } from '@/lib/api';
import { num } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useCall } from '@/lib/query';
import { featureOn } from '@/lib/features';
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
    /** মেনুতে "ফোন কল" (phone থাকলে) */
    withPhone?: boolean;
    /** আইকনের আগে ফোন নম্বর লেখা */
    showNumber?: boolean;
    /** মেসেজ বোতাম (চ্যাট অংশ জুড়লে) */
    message?: boolean;
  }>(),
  { show: true, small: true, peerUid: undefined, peerName: '', phone: null, withPhone: true, showNumber: false, message: false },
);
const emit = defineEmits<{ message: [target: { ctx: CallCtx; refId: string; peerUid?: string }]; phone: [] }>();
const s = useSession();
const care = useCareMode();
const me = computed(() => !!s.uid && !s.guest && !care.value && !!props.refId);
const can = computed(() => (me.value ? contactCan({ ctx: props.ctx, refId: props.refId, peerUid: props.peerUid }) : null));
// সার্ভারে contact_can না থাকলে: call_config + পাতার হিসাব
const cfg = useCall<{ enabled?: boolean }>('call_config', {}, () => ({ enabled: me.value && featureOn('calls_audio') && can.value === null, staleTime: 10 * 60_000, retry: false }));
const fallback = computed(() => props.show && cfg.data?.enabled === true);
const blocked = computed(() => me.value && !!can.value?.blocked);
const audio = computed(() => !blocked.value && me.value && featureOn('calls_audio') && (can.value ? can.value.canCall : can.value === null && fallback.value));
const video = computed(() => !blocked.value && me.value && featureOn('calls_audio') && featureOn('calls_video') && (can.value ? can.value.canVideo : can.value === null && fallback.value));
const msg = computed(() => !blocked.value && me.value && props.message && featureOn('chat') && !!can.value?.canMessage);
const phoneOn = computed(() => props.withPhone && !!props.phone);

type Opt = 'phone' | 'audio' | 'video';
const options = computed<Opt[]>(() => [phoneOn.value && 'phone', audio.value && 'audio', video.value && 'video'].filter(Boolean) as Opt[]);
const label = (o: Opt) => (o === 'phone' ? T.phoneCall() : o === 'audio' ? T.appCall() : T.video());
const icon = (o: Opt) => (o === 'phone' ? 'call' : o === 'audio' ? 'phone_in_talk' : 'videocam');
const testId = (o: Opt) => (o === 'phone' ? 'call-phone' : o === 'audio' ? 'call-audio' : 'call-video');
const single = computed(() => (options.value.length === 1 ? options.value[0] : null));
const any = computed(() => options.value.length > 0 || msg.value || blocked.value || (props.showNumber && phoneOn.value));

const go = (v: boolean) => startCall({ ctx: props.ctx, refId: props.refId, peerUid: props.peerUid, video: v, peerName: props.peerName, phone: props.phone });
function run(o: Opt) {
  open.value = false;
  if (o === 'phone') emit('phone');
  else go(o === 'video');
}

// কলের মেনু: বাইরে চাপলে / Esc-এ বন্ধ; পর্দার ডান দিকে জায়গা না থাকলে ডানে মিলিয়ে খোলে
const open = ref(false);
const root = ref<HTMLElement | null>(null);
const alignEnd = ref(false);
const onDown = (e: Event) => {
  if (!root.value?.contains(e.target as Node)) open.value = false;
};
const onKey = (e: KeyboardEvent) => {
  if (e.key === 'Escape') open.value = false;
};
function off() {
  document.removeEventListener('pointerdown', onDown);
  document.removeEventListener('keydown', onKey);
}
watch(open, async (o) => {
  off();
  if (!o) return;
  const r = root.value?.getBoundingClientRect();
  alignEnd.value = !!r && r.left + 200 > window.innerWidth;
  document.addEventListener('pointerdown', onDown);
  document.addEventListener('keydown', onKey);
  await nextTick();
  root.value?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
});
onBeforeUnmount(off);

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
const ic = computed(() => cx('icon-btn contact-ic', !props.small && 'big'));
</script>

<template>
  <div v-if="any" class="row wrap gap-4 call-btns" data-testid="call-buttons" @click.stop>
    <span v-if="showNumber && phoneOn" class="t-title-m num contact-num" data-testid="contact-number">{{ num(phone!) }}</span>
    <div v-if="options.length" ref="root" class="contact-call">
      <!-- একটাই: সরাসরি (ফোন হলে tel: লিংক) -->
      <a v-if="single === 'phone'" :class="ic" :href="`tel:${phone}`" :aria-label="T.phoneCall()" :title="T.phoneCall()" data-testid="contact-call" @click="emit('phone')"><Icon name="call" /></a>
      <button v-else-if="single" type="button" :class="ic" :disabled="busy()" :aria-label="label(single)" :title="label(single)" data-testid="contact-call" @click="run(single)"><Icon name="call" /></button>
      <button v-else type="button" :class="ic" :disabled="busy()" :aria-label="T.callMenu()" :title="T.callMenu()" aria-haspopup="menu" :aria-expanded="open" data-testid="contact-call" @click="open = !open"><Icon name="call" /></button>
      <div v-if="open && !single" :class="cx('call-menu', alignEnd && 'end')" role="menu" data-testid="call-menu">
        <template v-for="o in options" :key="o">
          <a v-if="o === 'phone'" role="menuitem" :href="`tel:${phone}`" :data-testid="testId(o)" @click="run(o)">
            <Icon :name="icon(o)" /><span>{{ label(o) }}</span><span class="num muted">{{ num(phone!) }}</span>
          </a>
          <button v-else type="button" role="menuitem" :disabled="busy()" :data-testid="testId(o)" @click="run(o)"><Icon :name="icon(o)" /><span>{{ label(o) }}</span></button>
        </template>
      </div>
    </div>
    <button v-if="msg" type="button" :class="ic" :aria-label="T.message()" :title="T.message()" data-testid="contact-message" @click="emit('message', { ctx, refId, peerUid })">
      <Icon name="forum_outline" />
    </button>
    <template v-if="blocked">
      <span class="t-body-s muted">{{ T.blockedShort() }}</span>
      <button type="button" class="btn outline square small" :disabled="unblocking || !peerUid" data-testid="contact-unblock" @click="unblock">{{ T.unblock() }}</button>
    </template>
  </div>
</template>
