<script setup lang="ts">
import { computed } from 'vue';
import { DirectoryCard } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { badgeIcon, canBook, chamberLabel, chamberTimeText, feeText, ratingLine, specialtyLabel, type Hospital, type Provider } from './model';
import { cardStatus } from './doctors';
import type { AreaSel } from './shared';
import { T as OT } from './online';
import { onlineOn } from './online/flags';

const props = defineProps<{ p: Provider; matched: number[] | null; area: AreaSel; hospital: Hospital | null; selected: boolean; onClick: () => void; online?: boolean }>();
const hasOnline = computed(() => onlineOn() && props.p.chambers.some((c) => c.mode === 'online'));
// ছাঁকনিতে মেলা চেম্বার (সার্ভারের matched) আগে, নইলে এলাকার সাথে মেলা চেম্বার
const ch = computed(() => {
  const { p, matched, area, hospital } = props;
  return (
    (matched?.length ? p.chambers[matched[0]] : undefined) ??
    (props.online ? p.chambers.find((c) => c.mode === 'online') : undefined) ??
    p.chambers.find((c) => (!hospital || c.hospitalId === hospital.id) && (!area.district || c.district === area.district) && (area.upazilaId == null || c.upazilaId === area.upazilaId)) ??
    p.chambers[0]
  );
});
</script>

<template>
  <DirectoryCard
    :title="p.name"
    :icon="badgeIcon(p.type)"
    :photo="p.photo || null"
    :subtitle="[p.degrees, p.specialties.map(specialtyLabel).join(', ')].filter(Boolean).join(' · ')"
    :status="cardStatus(ch)"
    :meta="ratingLine(p.rating, p.ratingCount)"
    :details="ch ? [[ch.mode === 'online' ? 'language' : 'local_hospital', chamberLabel(ch)], ['schedule', chamberTimeText(ch)], ['payments', feeText(ch.noFee, ch.fee)]] : []"
    :tags="[hasOnline ? OT.online() : null, canBook(p) ? tr('Appointment in app', 'অ্যাপে অ্যাপয়েন্টমেন্ট') : null, p.homeVisit?.enabled ? tr('Home visit', 'বাসায় যান') : null].filter(Boolean) as string[]"
    :note="p.chambers.length > 1 ? tr(`+${p.chambers.length - 1} more chamber(s)`, `আরও ${num(p.chambers.length - 1)}টা চেম্বার`) : null"
    :on-click="onClick"
    :selected="selected"
  />
</template>
