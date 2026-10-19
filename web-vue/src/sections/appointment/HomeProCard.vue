<script setup lang="ts">
import { DirectoryCard } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { badgeIcon, feeText, homeDistance, homeServiceLabel, ratingLine, specialtyLabel, timesText, type HomePro } from './model';
import { upLabels } from './homecare';

defineProps<{ p: HomePro; selected: boolean; onOpen: () => void; onCall: () => void; onRate: (() => void) | null }>();
</script>

<template>
  <DirectoryCard
    :title="p.name"
    :icon="badgeIcon(p.type)"
    :photo="p.photo || null"
    :subtitle="[p.degrees, p.specialties.map(specialtyLabel).join(', ')].filter(Boolean).join(' · ')"
    :meta="ratingLine(p.rating, p.ratingCount, homeDistance(p.km))"
    :details="[
      ['medical_services', p.services.map(homeServiceLabel).join(' · ')],
      ['location_on', upLabels(p.upazilaIds).length ? tr('Also visits: ', 'যান: ') + upLabels(p.upazilaIds).join(', ') : ''],
      ['schedule', timesText(p.times, p.schedule)],
      ['payments', feeText(p.noFee, p.fee)],
    ]"
    :primary="{ label: tr('Call', 'ফোন করুন'), icon: 'call', onClick: onCall }"
    :secondary="onRate ? { label: p.myRating > 0 ? tr(`Your rating ★${p.myRating}`, `আপনার রেটিং ★${num(p.myRating)}`) : tr('Rate', 'রেটিং দিন'), onClick: onRate } : null"
    :on-click="onOpen"
    :selected="selected"
  />
</template>
