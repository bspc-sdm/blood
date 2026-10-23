<script setup lang="ts">
// "আমার রিকোয়েস্ট"-এর একটা কার্ড: অবস্থা, রাজি ডোনার (৩ জন পর্যন্ত), নিচে ছোট বাটন
import { computed } from 'vue';
import { Action, BloodGroupBadge, Button, IconButton, UrgentTag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { counts, deadline, neededText, willing, type BloodRequest, type DonorResponse } from './model';
import { BADGE_SLOT } from './parts/consts';
import Countdown from './parts/Countdown.vue';
import SlotRow from './parts/SlotRow.vue';
import AgreedLine from './parts/AgreedLine.vue';
import BagRow from './parts/BagRow.vue';
import DonorRow from './parts/DonorRow.vue';
import NotifyAgainRow from './NotifyAgainRow.vue';

const props = defineProps<{ r: BloodRequest; list: DonorResponse[]; busyId: string | null }>();
const emit = defineEmits<{ share: []; notify: []; notifyAgain: []; confirm: [d: DonorResponse]; remove: [d: DonorResponse]; open: []; close: [] }>();
const busy = computed(() => props.busyId === props.r.id);
const w = computed(() => willing(props.list));
const sorted = computed(() => [...w.value].sort((a, b) => Number(counts(b)) - Number(counts(a))));
</script>

<template>
  <article class="card blood-mycard col">
    <div class="row gap-12">
      <BloodGroupBadge :group="r.patientGroup" :size="BADGE_SLOT" />
      <div class="col gap-2 grow">
        <div class="row gap-6">
          <span class="semi ellipsis">{{ r.hospital }}</span>
          <UrgentTag v-if="r.urgent" />
        </div>
        <span class="t-body-s muted">{{ neededText(r) }}</span>
      </div>
      <IconButton icon="share" :label="tr('Share', 'শেয়ার')" class="c-primary" @click="emit('share')" />
    </div>
    <div class="col gap-6">
      <Countdown :target="deadline(r)" :slot-w="BADGE_SLOT" />
      <template v-if="r.notified">
        <SlotRow icon="notifications_active" muted>
          <span class="t-body-m muted">{{ tr(`${num(r.notifiedCount)} donors notified`, `${num(r.notifiedCount)} জন ডোনারকে জানানো হয়েছে`) }}</span>
        </SlotRow>
        <NotifyAgainRow :r="r" :busy="busy" :disabled="busyId != null && !busy" @notify-again="emit('notifyAgain')" />
      </template>
      <SlotRow v-else>
        <Action icon="notifications" primary :busy="busy" :disabled="busyId != null && !busy" @click="emit('notify')">{{ tr('Notify nearby donors', 'আশেপাশের ডোনারদের জানান') }}</Action>
      </SlotRow>
      <AgreedLine :list="list" :slot-w="BADGE_SLOT" />
    </div>
    <div :style="{ paddingLeft: BADGE_SLOT + 12 + 'px' }"><BagRow :r="r" :list="list" /></div>
    <template v-if="w.length">
      <hr class="divider" />
      <DonorRow
        v-for="d in sorted.slice(0, 3)"
        :key="d.donorUid"
        :d="d"
        :req-id="r.id"
        active
        :busy="busy"
        :can-confirm="r.confirmedCount < r.bags"
        @confirm="emit('confirm', d)"
        @remove="emit('remove', d)"
      />
      <Button v-if="w.length > 3" variant="text" small :style="{ alignSelf: 'flex-start' }" @click="emit('open')">{{ tr(`See all ${num(w.length)}`, `সবাই দেখুন (${num(w.length)})`) }}</Button>
    </template>
    <hr class="divider" />
    <div class="row gap-8 blood-actions">
      <Action icon="list_alt" @click="emit('open')">{{ tr('Details', 'বিস্তারিত') }}</Action>
      <Action icon="check_circle" :primary="w.length > 0" @click="emit('close')">{{ tr('Got blood', 'রক্ত পেয়েছি') }}</Action>
    </div>
  </article>
</template>
