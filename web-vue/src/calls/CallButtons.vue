<script setup lang="ts">
// "অ্যাপে কল" / "ভিডিও কল" — ফোনের বোতামের পাশে (ফোনই আগে, বিশেষ করে জরুরি পাতায়)।
// দেখায় শুধু লগইন থাকলে, নিজের হয়ে (কেয়ারগিভার মোডে নয়), আর সার্ভারে কল চালু থাকলে (call_config)।
// সম্পর্ক (রিকোয়েস্ট খোলা, অ্যাপয়েন্টমেন্টের সময়…) যে পাতা বসায় সে দেখে `show` দেয়; শেষ কথা সার্ভারের।
import { computed } from 'vue';
import { Icon } from '@/ui/kit';
import { cx } from '@/ui/composables';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useCall } from '@/lib/query';
import type { CallCtx } from './api';
import { busy, startCall } from './state';
import { T } from './text';

const props = withDefaults(
  defineProps<{ ctx: CallCtx; refId: string; peerUid?: string; peerName?: string; phone?: string | null; show?: boolean; video?: boolean; small?: boolean }>(),
  { show: true, video: true, small: true, peerUid: undefined, peerName: '', phone: null },
);
const s = useSession();
const care = useCareMode();
const cfg = useCall<{ enabled?: boolean }>('call_config', {}, () => ({ enabled: !!s.uid && !s.guest, staleTime: 10 * 60_000, retry: false }));
const visible = computed(() => props.show && !!props.refId && !!s.uid && !s.guest && !care.value && cfg.data?.enabled === true);
const go = (video: boolean) =>
  startCall({ ctx: props.ctx, refId: props.refId, peerUid: props.peerUid, video, peerName: props.peerName, phone: props.phone });
</script>

<template>
  <div v-if="visible" class="row wrap gap-8 call-btns" data-testid="call-buttons" @click.stop>
    <button type="button" :class="cx('btn outline square has-icon', small && 'small')" :disabled="busy()" data-testid="call-audio" @click="go(false)">
      <Icon name="call" />{{ T.inApp() }}
    </button>
    <button v-if="video" type="button" :class="cx('btn outline square has-icon', small && 'small')" :disabled="busy()" data-testid="call-video" @click="go(true)">
      <Icon name="videocam" />{{ T.video() }}
    </button>
  </div>
</template>
