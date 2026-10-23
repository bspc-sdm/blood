<script setup lang="ts">
// "রোগীরা আপনাকে যেভাবে দেখেন" — বড় অংশ নয়, প্রতিটা তালিকায় থাকা ব্যবসার এক লাইন (ছোট ছবি, নাম, মূল লাইন) আর "দেখুন":
// চাপলে রোগীদের তালিকার পুরো কার্ড (একই ListingCard, একই তথ্য) একটা জানালায়, সেখান থেকে রোগীদের পাতা।
import { computed, ref } from 'vue';
import { Avatar, Button, Dialog } from '@/ui/kit';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { useNav } from '@/lib/nav';
import ListingCard from '@/ui/listing/ListingCard.vue';
import { LT, keyLineText, type Listing } from '@/ui/listing/model';
import { useOwnListings, type OwnItem } from '../ownListing';
import { parseProvider } from '../../appointment/model';
import { doctorListing } from '../../appointment/listing';
import { isChamberType } from '../../appointment/model';
import { TT } from './text';
import type { Role } from './model';

const props = defineProps<{ roles: Role[] }>();
const s = useSession();
const nav = useNav();
const ph = useOwnListings('pharmacy');
const lab = useOwnListings('lab');
const bb = useOwnListings('bloodbank');
const amb = useOwnListings('ambulance');
const doctorOn = computed(() => props.roles.includes('chamber') && isChamberType(s.work.professional));
const days = useCall<{ card?: any | null }>('chamber_days', {}, () => ({ enabled: doctorOn.value, retry: false, staleTime: 0 }));
const live = (x: { status?: string } | null | undefined) => !!x && x.status === 'approved';

const lines = computed(() => {
  const out: { key: string; l: Listing; to: string }[] = [];
  const has = (r: Role) => props.roles.includes(r);
  const card = days.data?.card;
  if (doctorOn.value && card) out.push({ key: 'doctor', l: doctorListing(parseProvider(card), card), to: `/services/doctor/${card.uid ?? s.uid}` });
  const p = s.work.pharmacy as OwnItem | null;
  if (has('pharmacy') && live(p)) out.push({ key: 'pharmacy', l: ph.listing(p!), to: `/services/pharmacy/${p!.id}` });
  const lb = s.work.lab as OwnItem | null;
  if (has('lab') && live(lb)) out.push({ key: 'lab', l: lab.listing(lb!), to: `/services/lab/${lb!.id}` });
  if (has('bloodbank')) for (const b of (s.work.bloodBanks as OwnItem[]).filter(live)) out.push({ key: 'bb' + b.id, l: bb.listing(b), to: `/services/bloodbank/${b.id}` });
  if (has('ambulance')) for (const a of (s.work.ambulances as OwnItem[]).filter(live).slice(0, 3)) out.push({ key: 'a' + a.id, l: amb.listing(a), to: `/services/ambulance/${a.id}` });
  return out;
});
const open = ref<{ l: Listing; to: string } | null>(null);
</script>

<template>
  <section v-if="lines.length" class="td-pv" data-testid="preview-lines">
    <h2 class="t-title-s">{{ LT.previewTitle() }}</h2>
    <ul class="col gap-6">
      <li v-for="x in lines" :key="x.key" class="td-pv-row">
        <Avatar :photo="x.l.photo || null" :icon="x.l.icon" :tint="x.l.tint" :size="36" />
        <span class="col grow" :style="{ minWidth: 0 }">
          <span class="semi ellipsis">{{ x.l.name }}</span>
          <span class="t-body-s muted ellipsis">{{ keyLineText(x.l.keyLine) }}</span>
        </span>
        <Button variant="outline" small data-testid="preview-see" @click="open = x">{{ TT.see() }}</Button>
      </li>
    </ul>
    <Dialog v-if="open" :title="LT.previewTitle()" @close="open = null">
      <ListingCard :l="open.l" preview />
      <template #actions>
        <Button variant="tonal" @click="nav(open.to)">{{ TT.openPublic() }}</Button>
      </template>
    </Dialog>
  </section>
</template>
