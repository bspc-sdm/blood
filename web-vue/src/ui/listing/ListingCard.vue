<script setup lang="ts">
// সেবার পাঁচ তালিকার একটাই কার্ড (আর "আমার কাজ"-এর "রোগীরা আপনাকে যেভাবে দেখেন" — একই কার্ড):
// ১ম সারি: ছবি/আইকন, নাম (২ লাইন পর্যন্ত, কাটা নয়), যাচাই ব্যাজ, ⋮ মেনু
// ২য় সারি: মূল লাইন (৩টা তথ্য " · " দিয়ে) — নিচে ব্যাজ / রক্তের মজুদ
// ৩য় সারি: রেটিং ★৪.৬ (৩১) (যে ধরনে থাকে)
// ৪র্থ সারি: মূল বাটন (slot `primary`; না দিলে "ফোন করুন") + ডানে যোগাযোগের দুই আইকন (কল মেনু · মেসেজ — ContactActions)
// কার্ডে ফোন নম্বর লেখা থাকে না (বিস্তারিতে থাকে)।
import { computed, useSlots } from 'vue';
import { Avatar, Icon, Tag, cardKey } from '@/ui/kit';
import { slotHasContent } from '@/ui/composables';
import ContactActions from '@/calls/ContactActions.vue';
import type { CallCtx } from '@/calls/api';
import { chatOn, openChatAt } from '@/chat/open';
import { LT, keyLineText, ratingText, type Listing } from './model';
import StockChips from './StockChips.vue';
import ListingMenu from './ListingMenu.vue';

const props = withDefaults(
  defineProps<{
    l: Listing;
    selected?: boolean;
    onOpen?: () => void;
    /** মূল বাটন "ফোন করুন" (অ্যাম্বুলেন্স, ব্লাড ব্যাংক) — তখন কল আইকন আর নয় (একই ফোন দুবার নয়) */
    callPrimary?: boolean;
    /** মূল বাটন নেই (যেমন বন্ধ ফার্মেসি) */
    noPrimary?: boolean;
    /** যোগাযোগের নম্বর (ডাক্তার: সিরিয়ালের ফোন); না দিলে l.phone */
    contactPhone?: string | null;
    onCall?: () => void;
    onReport?: () => void;
    /** "আমার কাজ"-এর ছোট রূপ: মেনু আর বাটন নেই */
    preview?: boolean;
  }>(),
  { selected: false, onOpen: undefined, contactPhone: undefined, onCall: undefined, onReport: undefined },
);
const slots = useSlots();
const CTX: Record<string, CallCtx> = { ambulance: 'ambulance', doctor: 'appointment', pharmacy: 'rx_order', lab: 'lab_order', bloodbank: 'blood' };
const phone = computed(() => (props.contactPhone === undefined ? props.l.phone : props.contactPhone) || null);
const key = computed(() => keyLineText(props.l.keyLine));
const hasPrimary = computed(() => !props.noPrimary && (props.callPrimary ? !!phone.value : slotHasContent(slots.primary)));
</script>

<template>
  <article
    :class="['card lst-card', onOpen && 'click', selected && 'sel', preview && 'preview']"
    :role="onOpen ? 'button' : undefined"
    :tabindex="onOpen ? 0 : undefined"
    :aria-pressed="onOpen ? selected : undefined"
    :data-kind="l.kind"
    data-testid="listing-card"
    @click="onOpen?.()"
    @keydown="cardKey($event, onOpen)"
  >
    <div class="lst-head">
      <Avatar :photo="l.photo || null" :icon="l.icon" :tint="l.tint" :size="44" />
      <div class="lst-main">
        <div class="lst-name-row">
          <span class="lst-name">{{ l.name }}</span>
          <Icon v-if="l.verified" name="verified" class="lst-verified" :title="LT.verified()" />
        </div>
        <p v-if="key" class="lst-key" data-testid="key-line">{{ key }}</p>
      </div>
      <ListingMenu v-if="!preview" :on-details="onOpen" :on-report="onReport" />
    </div>
    <div v-if="l.badges.length || (l.ratingRow && l.rating)" class="lst-meta">
      <span v-if="l.ratingRow && l.rating" class="lst-rating num" data-testid="rating">{{ ratingText(l.rating) }}</span>
      <Tag v-for="b in l.badges" :key="b.text" :tone="b.tone">{{ b.text }}</Tag>
    </div>
    <StockChips v-if="l.stock" :stock="l.stock" />
    <div v-if="!preview && (hasPrimary || phone)" class="lst-actions">
      <template v-if="hasPrimary">
        <a v-if="callPrimary" class="btn square has-icon lst-primary" :href="`tel:${phone}`" data-testid="listing-primary" @click.stop="onCall?.()"><Icon name="call" />{{ LT.call() }}</a>
        <span v-else class="contents" data-testid="listing-primary-wrap" @click.stop @keydown.stop><slot name="primary" /></span>
      </template>
      <span class="grow" />
      <ContactActions
        :ctx="CTX[l.kind]"
        ref-id=""
        :phone="phone"
        :with-phone="!callPrimary"
        :message="chatOn()"
        :show="false"
        @message="openChatAt"
        @phone="onCall?.()"
      />
    </div>
  </article>
</template>
