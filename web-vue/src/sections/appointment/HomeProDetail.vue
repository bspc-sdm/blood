<script setup lang="ts">
import { Button, IconButton, PhoneLine } from '@/ui/kit';
import { useTwoPane } from '@/shell/chrome';
import { num, tr } from '@/lib/i18n';
import { badgeIcon, coverageText, feeText, homeDistance, homeServiceLabel, ratingLine, specialtyLabel, timesText, type HomePro } from './model';
import { upLabels } from './homecare';
import DetailHead from './DetailHead.vue';
import DetailBlock from './DetailBlock.vue';

defineProps<{ p: HomePro; onClose: () => void; onCall: () => void; onRate: (() => void) | null; onReport: (() => void) | null }>();
const two = useTwoPane();
</script>

<template>
  <div class="row top">
    <div class="grow">
      <DetailHead
        :title="p.name"
        :icon="badgeIcon(p.type)"
        :photo="p.photo || null"
        :subtitle="[p.degrees, p.specialties.map(specialtyLabel).join(', ')].filter(Boolean).join(' · ')"
        :meta="ratingLine(p.rating, p.ratingCount, homeDistance(p.km))"
      />
    </div>
    <IconButton v-if="two" icon="close" :label="tr('Close', 'বন্ধ')" @click="onClose()" />
  </div>
  <div class="appt-phone">
    <PhoneLine :phone="p.phone" big :on-call="onCall" />
  </div>
  <hr class="divider" />
  <DetailBlock :title="tr('Services at home', 'বাসায় যে সেবা দেন')" :lines="p.services.map((sv) => ['medical_services', homeServiceLabel(sv)] as [string, string])" />
  <hr class="divider" />
  <DetailBlock :title="tr('Where they go', 'কোথায় যান')" :lines="[['location_on', coverageText(p.radiusKm, upLabels(p.upazilaIds))]]" />
  <hr class="divider" />
  <DetailBlock :title="tr('Time and fee', 'সময় ও ফি')" :lines="[['schedule', timesText(p.times, p.schedule)], ['payments', feeText(p.noFee, p.fee)]]" />
  <div v-if="onRate || onReport" class="row">
    <Button v-if="onRate" variant="text" icon="star" @click="onRate()">{{ p.myRating > 0 ? tr(`Your rating ★${p.myRating}`, `আপনার রেটিং ★${num(p.myRating)}`) : tr('Rate', 'রেটিং দিন') }}</Button>
    <span class="grow" />
    <Button v-if="onReport" variant="text" icon="flag" @click="onReport()">{{ tr('Report', 'রিপোর্ট') }}</Button>
  </div>
</template>
