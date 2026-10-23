<script setup lang="ts">
// একটা মেসেজ: লেখা / ছবি / লোকেশন; নিজের হলে ডানে, সময় আর ✓ (গেছে) / ✓✓ (দেখেছেন) / যাচ্ছে… / যায়নি।
// চাপলে নিচে কাজ: কপি, (নিজের, ১০ মিনিটের মধ্যে) দুজনের জন্যই মুছুন, (ওপাশের) রিপোর্ট।
import { computed } from 'vue';
import { Icon } from '@/ui/kit';
import { cx } from '@/ui/composables';
import { formatTime } from '@/lib/format';
import type { ChatMessage } from './api';
import { canUnsend } from './store';
import { imgSrc } from './photo';
import { T } from './text';

const props = defineProps<{ m: ChatMessage; mine: boolean; selected: boolean; now: number; canReport: boolean; blood?: boolean }>();
const emit = defineEmits<{ select: []; photo: []; photoError: []; retry: []; discard: []; copy: []; unsend: []; report: [] }>();
const mapUrl = computed(() => (props.m.location ? `https://www.google.com/maps?q=${props.m.location.lat},${props.m.location.lng}` : ''));
const thumb = computed(() => props.m.photoThumb || props.m.photoUrl || '');
const tick = computed(() => (props.m.readAt ? 'done_all' : 'done'));
</script>

<template>
  <div :class="cx('chat-msg', mine ? 'mine' : 'theirs', m.status, m.unsent && 'unsent')" data-testid="msg" :data-id="m.id" :data-status="m.status || (m.readAt ? 'read' : 'sent')">
    <div
      class="chat-bubble"
      role="button"
      tabindex="0"
      :aria-expanded="selected"
      @click="emit('select')"
      @keydown.enter.prevent="emit('select')"
    >
      <p v-if="m.unsent" class="t-body-m chat-unsent"><Icon name="block" :size="16" />{{ T.unsent() }}</p>
      <template v-else>
        <button v-if="thumb" type="button" class="chat-photo" :aria-label="T.photo()" @click.stop="emit('photo')">
          <img :src="imgSrc(thumb)" alt="" @error="emit('photoError')" />
        </button>
        <a v-if="m.location" class="chat-loc" :href="mapUrl" target="_blank" rel="noopener noreferrer" data-testid="msg-location" @click.stop>
          <Icon name="location_on" :size="28" />
          <span class="col">
            <span class="t-title-s">{{ T.location() }}</span>
            <span class="t-label-m">{{ T.openMap() }}</span>
          </span>
        </a>
        <p v-if="m.text" class="t-body-m chat-text" data-testid="msg-text">{{ m.text }}</p>
      </template>
      <span class="chat-meta">
        <span>{{ m.at ? formatTime(m.at) : '' }}</span>
        <template v-if="mine && !m.unsent">
          <span v-if="m.status === 'sending'" data-testid="msg-sending">{{ T.sending() }}</span>
          <span v-else-if="m.status === 'failed'" class="err" data-testid="msg-failed"><Icon name="error_outline" :size="14" />{{ T.failed() }}</span>
          <span v-else :class="cx('tick', !!m.readAt && 'read')" :title="m.readAt ? T.read() : T.sent()" :aria-label="m.readAt ? T.read() : T.sent()" data-testid="msg-tick"><Icon :name="tick" :size="16" /></span>
        </template>
      </span>
    </div>
    <p v-if="m.status === 'failed' && m.error" class="chat-err t-label-m">{{ m.error }}</p>
    <p v-if="m.warn === 'money_number' && !mine && !m.unsent" :class="cx('chat-warn t-label-m', blood && 'blood')" role="note" data-testid="msg-warn"><Icon name="warning" :size="16" />{{ blood ? T.warnMoneyBlood() : T.warnMoney() }}</p>
    <div v-if="(selected || m.status === 'failed') && !m.unsent" class="chat-acts" data-testid="msg-actions">
      <template v-if="m.status === 'failed'">
        <button type="button" class="btn text small" @click="emit('retry')">{{ T.retry() }}</button>
        <button type="button" class="btn danger-text small" @click="emit('discard')">{{ T.discard() }}</button>
      </template>
      <template v-else>
        <button v-if="m.text" type="button" class="btn text small has-icon" @click="emit('copy')"><Icon name="content_copy" />{{ T.copy() }}</button>
        <button v-if="mine && canUnsend(m, now)" type="button" class="btn danger-text small has-icon" data-testid="msg-unsend" @click="emit('unsend')"><Icon name="delete" />{{ T.unsend() }}</button>
        <span v-else-if="mine && !m.status" class="t-label-s muted">{{ T.unsendLate() }}</span>
        <button v-if="!mine && canReport" type="button" class="btn text small has-icon" data-testid="msg-report" @click="emit('report')"><Icon name="flag" />{{ T.report() }}</button>
      </template>
    </div>
  </div>
</template>
