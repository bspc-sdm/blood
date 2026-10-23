<script setup lang="ts">
// পাঁচ ধরনের একই বিস্তারিত (ফোনে পুরো পাতা, ডেস্কটপে ডানের প্যানেল):
// ১. মাথা: নাম, ব্যাজ, মূল লাইন  ২. মূল তথ্য  ৩. ধরনের নিজের ভাগ (চেম্বার ও সিরিয়াল / মজুদ / পরীক্ষা ও দাম / গাড়ি / খোলার সময়)
// ৪. ঠিকানা ও ফোন (কপি করা যায়)  ৫. রেটিং। ফোনে নিচে আটকানো বার: মূল বাটন + কল আর মেসেজের আইকন।
import { computed, ref, useSlots } from 'vue';
import { Avatar, Button, Icon, IconButton, Tag } from '@/ui/kit';
import { slotHasContent } from '@/ui/composables';
import DetailPane from '@/shell/DetailPane.vue';
import { useTwoPane } from '@/shell/chrome';
import ContactActions from '@/calls/ContactActions.vue';
import type { CallCtx } from '@/calls/api';
import { chatOn, openChatAt } from '@/chat/open';
import { num, tr } from '@/lib/i18n';
import { LT, keyLineText, ratingText, type Listing } from './model';
import StockChips from './StockChips.vue';

export type DLine = [icon: string, text: string | null | undefined];
export interface DSection { title: string; lines?: DLine[]; rows?: { name: string; value: string; sub?: string }[]; note?: string }
const props = withDefaults(
  defineProps<{
    l: Listing;
    title: string;
    backTo: string;
    facts?: DLine[];
    sections?: DSection[];
    callPrimary?: boolean;
    noPrimary?: boolean;
    contactPhone?: string | null;
    onCall?: () => void;
    onRate?: () => void;
    /** রেটিংয়ের নিচের ছোট লাইন (ফার্মেসি: ব্যবহার ★৪.৮ · …) */
    ratingNote?: string;
    /** রেটিং ভাগ এই ধরনে আছে কি না (ব্লাড ব্যাংক/ল্যাবে নেই) */
    rated?: boolean;
    onReport?: () => void;
    /** ডেস্কটপে ✕ (কিছু বাছা থাকলে) */
    closable?: boolean;
  }>(),
  { facts: () => [], sections: () => [], contactPhone: undefined, onCall: undefined, onRate: undefined, ratingNote: '', rated: true, onReport: undefined },
);
const emit = defineEmits<{ close: [] }>();
const slots = useSlots();
const two = useTwoPane();
const CTX: Record<string, CallCtx> = { ambulance: 'ambulance', doctor: 'appointment', pharmacy: 'rx_order', lab: 'lab_order', bloodbank: 'blood' };
const phone = computed(() => (props.contactPhone === undefined ? props.l.phone : props.contactPhone) || null);
const factLines = computed(() => props.facts.filter((f): f is [string, string] => !!f[1]));
const secs = computed(() =>
  props.sections
    .map((s) => ({ ...s, lines: (s.lines ?? []).filter((x): x is [string, string] => !!x[1]) }))
    .filter((s) => s.lines.length || s.rows?.length || s.note),
);
const hasPrimary = computed(() => !props.noPrimary && (props.callPrimary ? !!phone.value : slotHasContent(slots.primary)));
const copied = ref(false);
function copy() {
  if (!phone.value) return;
  navigator.clipboard?.writeText(phone.value).then(() => (copied.value = true), () => (copied.value = false));
  setTimeout(() => (copied.value = false), 2000);
}
</script>

<template>
  <DetailPane :title="title" :back-to="backTo" hide-title-wide>
    <div class="lst-dhead" data-testid="listing-detail">
      <Avatar :photo="l.photo || null" :icon="l.icon" :tint="l.tint" :size="64" />
      <div class="col gap-4 grow" :style="{ minWidth: 0 }">
        <h2 class="t-title-l lst-dname">{{ l.name }}<Icon v-if="l.verified" name="verified" class="lst-verified" :title="LT.verified()" /></h2>
        <p v-if="keyLineText(l.keyLine)" class="lst-key">{{ keyLineText(l.keyLine) }}</p>
        <div v-if="l.badges.length" class="row wrap gap-6"><Tag v-for="b in l.badges" :key="b.text" :tone="b.tone">{{ b.text }}</Tag></div>
      </div>
      <IconButton v-if="two && closable" icon="close" :label="tr('Close', 'বন্ধ')" @click="emit('close')" />
    </div>
    <slot name="top" />
    <section v-if="factLines.length" class="services-sec">
      <hr class="divider" />
      <h3 class="t-title-s">{{ LT.keyFacts() }}</h3>
      <div v-for="[ic, text] in factLines" :key="ic + text" class="services-line"><Icon :name="ic" /><span>{{ text }}</span></div>
    </section>
    <section v-if="l.stock" class="services-sec">
      <hr class="divider" />
      <h3 class="t-title-s">{{ LT.stockTitle() }}</h3>
      <StockChips :stock="l.stock" big />
    </section>
    <section v-for="sec in secs" :key="sec.title" class="services-sec">
      <hr class="divider" />
      <h3 class="t-title-s">{{ sec.title }}</h3>
      <ul v-if="sec.rows?.length" class="services-prices">
        <li v-for="r in sec.rows" :key="r.name">
          <span class="col gap-2 grow">
            <span>{{ r.name }}</span>
            <span v-if="r.sub" class="t-body-s muted">{{ r.sub }}</span>
          </span>
          <span class="semi num">{{ r.value }}</span>
        </li>
      </ul>
      <div v-for="[ic, text] in sec.lines" :key="ic + text" class="services-line"><Icon :name="ic" /><span>{{ text }}</span></div>
      <p v-if="sec.note" class="t-body-s muted">{{ sec.note }}</p>
    </section>
    <slot name="type" />
    <section v-if="l.address || phone" class="services-sec">
      <hr class="divider" />
      <h3 class="t-title-s">{{ LT.addressPhone() }}</h3>
      <div v-if="l.address" class="services-line"><Icon name="location_on" /><span>{{ l.address }}</span></div>
      <div v-if="phone" class="services-line lst-phone">
        <Icon name="call" /><span class="num semi" :style="{ userSelect: 'all' }" data-testid="detail-phone">{{ num(phone) }}</span>
        <button type="button" class="btn text small" @click="copy">{{ copied ? tr('Copied', 'কপি হয়েছে') : tr('Copy', 'কপি') }}</button>
      </div>
    </section>
    <section v-if="rated" class="services-sec">
      <hr class="divider" />
      <h3 class="t-title-s">{{ LT.ratings() }}</h3>
      <div class="row wrap gap-8">
        <span :class="l.rating ? 't-title-m num' : 't-body-m muted'" data-testid="detail-rating">{{ l.rating ? ratingText(l.rating) : LT.noRatings() }}</span>
        <span class="grow" />
        <Button v-if="onRate" variant="text" icon="star" @click="onRate()">{{ tr('Rate', 'রেটিং দিন') }}</Button>
      </div>
      <p v-if="ratingNote" class="t-body-s muted">{{ ratingNote }}</p>
    </section>
    <slot />
    <div v-if="onReport" class="row">
      <span class="grow" />
      <Button variant="text" icon="flag" class="services-report" @click="onReport()">{{ tr('Report', 'রিপোর্ট') }}</Button>
    </div>
    <div v-if="hasPrimary || phone" class="lst-bar" data-testid="detail-bar">
      <template v-if="hasPrimary">
        <a v-if="callPrimary" class="btn square has-icon lst-primary" :href="`tel:${phone}`" data-testid="listing-primary" @click="onCall?.()"><Icon name="call" />{{ LT.call() }}</a>
        <slot v-else name="primary" />
      </template>
      <span v-else class="grow" />
      <ContactActions
        :ctx="CTX[l.kind]"
        ref-id=""
        :phone="phone"
        :with-phone="!callPrimary"
        :message="chatOn()"
        :show="false"
        :small="false"
        @message="openChatAt"
        @phone="onCall?.()"
      />
    </div>
  </DetailPane>
</template>
