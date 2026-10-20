<script setup lang="ts">
// বিস্তারিত: default slot — ভাগগুলোর পরে, নিচের বারের আগে।
// main slot (২.২৭.০): নিচের আটকানো বারে ফোনের পাশে মূল কাজ — মূল কাজ ভরাট আর চওড়া, ফোন আউটলাইন আর ছোট ("ফোন", call-compact);
// শুধু অ্যাম্বুলেন্সে দুটো আধাআধি (সেখানে ফোন করাই মূল কাজ);
// main না থাকলে বড় ভরাট "ফোন করুন" আগের মতো। top slot (২.৩২.০): মাথার ঠিক নিচে, ভাগগুলোর আগে।
import { computed, useSlots } from 'vue';
import { RouterLink } from 'vue-router';
import { Avatar, Button, Icon, IconButton, PhoneLine, Tag } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { useTwoPane } from '@/shell/chrome';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { kindLabel, recordCall, type Entry } from './data';

const props = defineProps<{ e: Entry; backTo: string; onRate?: () => void; onReport?: () => void }>();
const nav = useNav();
const two = useTwoPane();
const s = useSession();
const slots = useSlots();
const hasMain = computed(() => !!slots.main);
const sections = computed(() =>
  props.e.sections
    .map((sec) => ({ ...sec, lines: sec.lines.filter((l) => l[1]) }))
    .filter((sec) => sec.lines.length || sec.link || sec.rows?.length),
);
const call = () => recordCall(props.e.kind, props.e.id, s.guest);
</script>

<template>
  <DetailPane :title="kindLabel(e.kind)" :back-to="backTo" hide-title-wide>
    <div class="services-head">
      <Avatar :icon="e.icon" :tint="e.tint" :size="72" />
      <div class="col gap-4 grow">
        <h2 class="t-title-l">{{ e.title }}</h2>
        <span v-if="e.subtitle" class="t-body-m c-primary">{{ e.subtitle }}</span>
        <span v-if="e.meta" class="t-body-m muted">{{ e.meta }}</span>
        <div v-if="e.status || e.tag" class="row gap-6">
          <Tag v-if="e.status" :tone="e.status.good ? 'good' : undefined">{{ e.status.text }}</Tag>
          <Tag v-if="e.tag" tone="primary">{{ e.tag }}</Tag>
        </div>
      </div>
      <IconButton v-if="two" icon="close" :label="tr('Close', 'বন্ধ')" @click="nav(backTo)" />
    </div>
    <slot name="top" />
    <section v-for="sec in sections" :key="sec.title" class="services-sec">
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
      <div v-for="[ic, text] in sec.lines" :key="ic + text" class="services-line">
        <Icon :name="ic" />
        <span>{{ text }}</span>
      </div>
      <div v-if="sec.link">
        <RouterLink class="btn outline square small has-icon" :to="sec.link.to"><Icon v-if="sec.link.icon" :name="sec.link.icon" />{{ sec.link.label }}</RouterLink>
      </div>
    </section>
    <slot />
    <div class="services-foot">
      <div v-if="hasMain" :class="['detail-bar-row', e.kind !== 'ambulance' && 'call-compact']">
        <a class="btn outline square has-icon call" :href="`tel:${e.phone}`" @click="call"><Icon name="call" />{{ tr('Call', 'ফোন') }}</a>
        <slot name="main" />
      </div>
      <PhoneLine v-else big :phone="e.phone" :on-call="call" />
      <div v-if="onRate || (e.reportable && onReport) || hasMain" class="row services-acts">
        <Button v-if="onRate" variant="text" icon="star" @click="onRate()">{{ tr('Rate', 'রেটিং দিন') }}</Button>
        <span class="grow" />
        <span v-if="hasMain" class="t-body-m muted num services-foot-num">{{ num(e.phone) }}</span>
        <Button v-if="e.reportable && onReport" variant="text" icon="flag" class="services-report" @click="onReport()">{{ tr('Report', 'রিপোর্ট') }}</Button>
      </div>
    </div>
  </DetailPane>
</template>
