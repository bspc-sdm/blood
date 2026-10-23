<script setup lang="ts">
import { computed } from 'vue';
import { Button, IconLine, PhoneLine, Tag } from '@/ui/kit';
import { formatDate } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import { apptChamber, apptWhen, badgeLabel, isActive, statusLabel, statusTone, type Appointment } from './model';
import { usePatientQueue } from './queue';
import PatientQueue from './PatientQueue.vue';
import { T as OT } from './online';
import { onlineOn } from './online/flags';
import OnlinePayCard from './online/OnlinePayCard.vue';
import JoinCall from './online/JoinCall.vue';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';
import { apptCallOpen } from '@/calls/window';
import { useNow } from '@/ui/composables';
import InvoiceLink from '../invoices/InvoiceLink.vue';

const props = defineProps<{ a: Appointment }>();
const emit = defineEmits<{ cancel: []; changed: [] }>();
// আজকের সিরিয়াল চললে নম্বর নিচের সারির কার্ডেই (দুবার নয়)
const pq = usePatientQueue(() => props.a);
const live = computed(() => pq.data?.live === true);
// অ্যাপে কল: নিশ্চিত অ্যাপয়েন্টমেন্ট, সময়ের ৩০ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত
const now = useNow(30_000);
const callOpen = computed(() => apptCallOpen(props.a, now.value));
</script>

<template>
  <div class="row">
    <Tag :tone="statusTone(a.status)">{{ statusLabel(a.status) }}</Tag>
    <span class="t-body-s muted grow">{{ badgeLabel(a.type) }}</span>
  </div>
  <div v-if="a.serial > 0 && !live" class="card tonal appt-serial">
    <span class="t-label-m">{{ tr('Serial #', 'সিরিয়াল নং ').trim() }}</span>
    <span class="appt-serial-n num">{{ num(a.serial) }}</span>
  </div>
  <PatientQueue :a="a" />
  <JoinCall :a="a" :name="a.otherName" hint />
  <div class="col">
    <IconLine icon="calendar_month">{{ apptWhen(a, formatDate) }}</IconLine>
    <IconLine v-if="a.online" icon="language">{{ OT.modeLabel('online') }}</IconLine>
    <IconLine v-else-if="a.chamber" icon="local_hospital">{{ apptChamber(a) + (a.room ? ' · ' + a.room : '') }}</IconLine>
    <IconLine v-if="a.schedule" icon="schedule">{{ a.schedule }}</IconLine>
  </div>
  <div v-if="a.note" class="col gap-4">
    <span class="t-label-m muted">{{ tr('Reason for visit', 'কী সমস্যা') }}</span>
    <p class="t-body-l pre">{{ a.note }}</p>
  </div>
  <OnlinePayCard v-if="a.online && onlineOn() && a.status !== 'cancelled' && a.status !== 'declined' && a.status !== 'expired'" :a="a" @changed="emit('changed')" />
  <p v-if="a.providerNote" class="t-body-m appt-note">{{ tr('Note: ', 'নোট: ') + a.providerNote }}</p>
  <PhoneLine v-if="a.otherPhone" :phone="a.otherPhone" big />
  <ContactActions :message="chatOn()" @message="openChatAt" ctx="appointment" :ref-id="a.id" :peer-name="a.otherName" :phone="a.otherPhone || null" :show="callOpen" :small="false" />
  <div v-if="a.role === 'patient'" class="row"><InvoiceLink ref-type="appointment" :ref-id="a.id" :invoice-id="a.invoiceId" /></div>
  <div v-if="isActive(a.status)" class="row">
    <Button variant="danger-text" icon="close" @click="emit('cancel')">{{ tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল') }}</Button>
  </div>
</template>
