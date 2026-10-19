<script setup lang="ts">
// কার্ড (অ্যাপের DirectoryCard)। main slot (২.৩২.০, ফার্মেসি): বড় ভরাট মূল কাজ ("প্রেসক্রিপশন পাঠান") — তখন ফোন ছোট আউটলাইন আইকন-বাটন;
// info slot: বিস্তারিত লাইনের আগে আলাদা ঘর (প্রেসক্রিপশনের হিসাব)
import { computed, useSlots } from 'vue';
import { Avatar, Button, Icon, IconLine, Tag, cardKey } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { recordCall, type Entry } from './data';
import MoreMenu from './MoreMenu.vue';

const props = defineProps<{ e: Entry; selected: boolean; onOpen: () => void; onRate?: () => void; onReport?: () => void }>();
const s = useSession();
const slots = useSlots();
const details = computed(() => props.e.details.filter((d) => d[1]));
const hasMain = computed(() => !!slots.main);
const call = () => recordCall(props.e.kind, props.e.id, s.guest);
</script>

<template>
  <article :class="['card click services-card', selected && 'sel']" role="button" tabindex="0" :aria-pressed="selected" @click="onOpen()" @keydown="cardKey($event, onOpen)">
    <div class="row top gap-12">
      <Avatar :icon="e.icon" :tint="e.tint" />
      <div class="col gap-2 grow">
        <div class="row">
          <span class="t-title-m clamp2" :style="{ minWidth: 0 }">{{ e.title }}</span>
          <Tag v-if="e.status" :tone="e.status.good ? 'good' : undefined">{{ e.status.text }}</Tag>
          <Tag v-if="e.tag" tone="primary">{{ e.tag }}</Tag>
        </div>
        <span v-if="e.subtitle" class="t-body-m c-primary clamp2">{{ e.subtitle }}</span>
        <span v-if="e.meta" class="t-body-s muted">{{ e.meta }}</span>
      </div>
      <MoreMenu v-if="onReport && !s.guest" @report="onReport()" />
    </div>
    <slot name="info" />
    <div v-if="details.length || e.note" class="col gap-6" :style="{ marginTop: '10px' }">
      <IconLine v-for="[ic, text] in details" :key="ic + text" :icon="ic">{{ text }}</IconLine>
      <p v-if="e.note" class="t-body-s muted">{{ e.note }}</p>
    </div>
    <div class="row" :style="{ marginTop: '12px' }">
      <slot name="main" />
      <a
        v-if="hasMain"
        class="btn outline square ph-call"
        :href="`tel:${e.phone}`"
        :aria-label="tr('Call', 'ফোন')"
        :title="tr('Call', 'ফোন')"
        data-testid="ph-card-call"
        @click.stop="call"
      ><Icon name="call" /></a>
      <a v-else class="btn square has-icon" :href="`tel:${e.phone}`" @click.stop="call"><Icon name="call" />{{ tr('Call', 'ফোন করুন') }}</a>
      <span class="services-card-phone t-body-m muted num">{{ num(e.phone) }}</span>
      <span class="grow" />
      <Button v-if="onRate" variant="text" @click.stop="onRate()">{{ tr('Rate', 'রেটিং দিন') }}</Button>
    </div>
  </article>
</template>
