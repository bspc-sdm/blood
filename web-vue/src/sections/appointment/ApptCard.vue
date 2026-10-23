<script setup lang="ts">
import { IconLine, Tag, cardKey, cx } from '@/ui/kit';
import { formatDate } from '@/lib/format';
import { tr } from '@/lib/i18n';
import { apptChamber, apptWhen, isActive, serialText, statusLabel, statusTone, type Appointment } from './model';
import PatientQueueLine from './PatientQueueLine.vue';
import { T as OT, payLabel, payTone } from './online';
import { onlineOn } from './online/flags';
import { systemTag } from '@/lib/practice';

const props = defineProps<{ a: Appointment; selected: boolean; onClick: () => void }>();
</script>

<template>
  <article
    :class="cx('card click col gap-6', selected && 'sel', !isActive(a.status) && 'appt-dim')"
    role="button"
    tabindex="0"
    :aria-pressed="selected"
    @click="onClick()"
    @keydown="cardKey($event, props.onClick)"
  >
    <div class="row">
      <span class="t-title-m grow clamp2">{{ a.otherName || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট') }}</span>
      <Tag v-if="a.role === 'patient' && systemTag(a.type, a.system)" tone="info" class="title-tag">{{ systemTag(a.type, a.system) }}</Tag>
      <Tag :tone="statusTone(a.status)">{{ statusLabel(a.status) }}</Tag>
    </div>
    <div v-if="a.online && onlineOn()" class="row wrap gap-6">
      <Tag tone="info">{{ OT.online() }}</Tag>
      <Tag v-if="!a.noFee && isActive(a.status)" :tone="payTone(a.payState)">{{ payLabel(a.payState) }}</Tag>
    </div>
    <IconLine icon="calendar_month">{{ apptWhen(a, formatDate) }}</IconLine>
    <span v-if="a.serial > 0" class="t-title-s bold c-primary">{{ serialText(a.serial) }}</span>
    <PatientQueueLine :a="a" />
    <IconLine v-if="a.online" icon="language">{{ OT.modeLabel('online') }}</IconLine>
    <IconLine v-else-if="a.chamber" icon="local_hospital">{{ apptChamber(a) + (a.room ? ' · ' + a.room : '') }}</IconLine>
    <p v-if="a.note" class="t-body-m clamp2">{{ a.note }}</p>
    <p v-if="a.providerNote" class="t-body-m appt-note">{{ tr('Note: ', 'নোট: ') + a.providerNote }}</p>
  </article>
</template>
