<script setup lang="ts">
// ছোট পর্দায় এক সারি (অ্যাপের PatientRow)
import { Action, Avatar, Icon, Tag, cardKey, cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { Row } from './patients';

const props = defineProps<{ r: Row; selected: boolean; onClick?: () => void; onWrite?: () => void }>();
</script>

<template>
  <div
    :class="onClick ? cx('card click rxd-prow', selected && 'sel') : 'card rxd-prow'"
    v-bind="onClick ? { role: 'button', tabindex: 0 } : {}"
    @click="onClick?.()"
    @keydown="onClick && cardKey($event, props.onClick)"
  >
    <span class="row gap-12">
      <Avatar :icon="r.icon" :size="40" />
      <span class="col gap-2 grow">
        <span class="t-title-m">{{ r.title }}</span>
        <span v-if="r.sub" class="t-body-s muted">{{ r.sub }}</span>
        <span v-if="r.tag"><Tag tone="info">{{ r.tag }}</Tag></span>
      </span>
      <Action v-if="onWrite" icon="edit_note" primary @click.stop="onWrite()">{{ tr('Write', 'লিখুন') }}</Action>
      <Icon v-else-if="onClick" name="chevron_right" class="rxd-chev" />
    </span>
  </div>
</template>
