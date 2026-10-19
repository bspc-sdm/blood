<script setup lang="ts">
// হোম: আশেপাশের রক্তের রিকোয়েস্ট — অ্যাপের HomeTab (MainScreen.kt)।
// অতিথি: সার্ভার থেকে (guest_requests); লগইন করা: সার্ভার থেকে (blood_requests_near) geohash দিয়ে, দেখার গ্রুপ মিলিয়ে, নিজেরগুলো বাদ।
import { computed, ref } from 'vue';
import { Button, EmptyState, ErrorBox, Fab, Icon, IconButton, SkeletonCards, toast } from '@/ui/kit';
import PlacePicker from '@/ui/PlacePicker.vue';
import ListDetail from '@/shell/ListDetail.vue';
import { usePageTitle } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { districtLabel } from '@/lib/geo';
import { useSession, type Place } from '@/lib/session';
import { effectiveViewGroups, emptyProfile, isDonor } from '@/lib/profile';
import { DEFAULT_RADIUS_KM } from '@/lib/rules';
import { useNav } from '@/lib/nav';
import { useQ } from '@/lib/query';
import { KEY, api, dropCommitments, nearbyOpen, processing, useInvalidate, type Nearby } from './data';
import type { BloodRequest } from './model';
import ProcessingCard from './parts/ProcessingCard.vue';
import RequestCard from './parts/RequestCard.vue';
import EmergencyBanner from './Emergency.vue';
import MyRequestSection from './MyRequestSection.vue';
import RequestDetail from './RequestDetail.vue';
import NeedNow from './NeedNow.vue';

const props = defineProps<{ selectedId: string | null }>();
const s = useSession();
const nav = useNav();
const invalidate = useInvalidate();
usePageTitle(() => tr('Home', 'হোম'));
const picking = ref(false);
const placeBusy = ref(false);
const placeErr = ref<string | null>(null);
const procBusy = ref<string | null>(null);

const profile = computed(() => s.profile ?? emptyProfile());
const guest = computed(() => s.guest);
const place = computed(() => s.place);
const maxKm = computed(() => (guest.value ? DEFAULT_RADIUS_KM : profile.value.maxKm));
const groups = computed(() => (guest.value ? null : effectiveViewGroups(profile.value)));

const nearby = useQ<Nearby[]>(() => ({
  queryKey: [KEY, 'nearby', s.uid, place.value?.lat, place.value?.lng, groups.value?.join(','), maxKm.value],
  queryFn: () =>
    guest.value ? api.guestRequests(place.value!.lat, place.value!.lng, maxKm.value) : nearbyOpen(place.value!.lat, place.value!.lng, groups.value, s.uid, maxKm.value),
  enabled: s.ready && !!place.value && (guest.value || s.profileLoaded),
  staleTime: 30_000,
}));

// যেসব রিকোয়েস্টে রক্ত দিতে রাজি হয়েছেন (প্রসেসিং), কাউন্টডাউন সহ উপরে
const commitments = computed(() => (guest.value ? [] : profile.value.commitments));
const proc = useQ<BloodRequest[]>(() => ({
  queryKey: [KEY, 'processing', s.uid, commitments.value.join(',')],
  queryFn: () => processing(s.uid!, commitments.value),
  enabled: !!s.uid && commitments.value.length > 0,
  staleTime: 30_000,
}));
const processingList = computed(() => (commitments.value.length ? proc.data ?? [] : []));
const others = computed(() => {
  const procIds = new Set(processingList.value.map((r) => r.id));
  return (nearby.data ?? []).filter((x) => !procIds.has(x.r.id));
});

// শেষ হয়ে যাওয়া রিকোয়েস্টে: রক্ত দিয়েছি / দিইনি
async function answer(id: string, op: 'donated' | 'cancel') {
  procBusy.value = id;
  try {
    await api.updateResponse(id, op);
    if (s.uid) await dropCommitments(s.uid, [id]).catch(() => {});
    await s.reloadProfile();
  } catch (e) {
    toast(errorText(e));
  } finally {
    procBusy.value = null;
    invalidate();
  }
}

async function pickPlace(p: Place) {
  placeBusy.value = true;
  placeErr.value = null;
  try {
    await s.setPlace(p);
    picking.value = false;
  } catch (e) {
    placeErr.value = errorText(e);
  } finally {
    placeBusy.value = false;
  }
}

const newRequest = () => s.requireLogin(tr('Log in to request blood', 'রক্তের রিকোয়েস্ট দিতে লগইন করুন'), () => nav('/request/new'));
const open = (id: string) => nav(`/request/${id}`);
const areaName = computed(() => (place.value ? districtLabel(place.value.placeName) || tr('My location', 'আমার লোকেশন') : tr('Choose area', 'এলাকা বেছে নিন')));
const becomeDonor = () => s.requireLogin(tr('Log in to become a blood donor', 'রক্তদাতা হতে লগইন করুন'), () => {}, { donor: true });
</script>

<template>
  <ListDetail :has-detail="!!selectedId">
    <div class="list-inner">
      <button v-if="!isDonor(profile)" type="button" class="blood-become" @click="becomeDonor">
        <Icon name="volunteer_activism" :size="22" />
        <span class="grow">{{ tr('I want to donate blood', 'রক্ত দিতে চাই') }}</span>
        <Icon name="chevron_right" :size="20" />
      </button>

      <ProcessingCard
        v-for="r in processingList"
        :key="'p_' + r.id"
        :r="r"
        :selected="r.id === selectedId"
        :busy="procBusy === r.id"
        @click="open(r.id)"
        :on-donated="() => answer(r.id, 'donated')"
        :on-not-donated="() => answer(r.id, 'cancel')"
      />

      <div class="row blood-head">
        <h2 class="t-title-l grow">{{ tr('Nearby Requests', 'আশেপাশের রিকোয়েস্ট') }}</h2>
        <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="invalidate()" />
        <button type="button" class="chip lead blood-area" :aria-label="tr('Change area', 'এলাকা বদলান') + ': ' + areaName" @click="picking = true">
          <Icon name="location_on" />
          <span class="ellipsis">{{ areaName }}</span>
        </button>
        <Button v-if="others.length" icon="add" square class="blood-wide-only" @click="newRequest">{{ tr('Request', 'রিকোয়েস্ট') }}</Button>
      </div>

      <EmergencyBanner :district="place?.placeName" />
      <MyRequestSection v-if="!guest" />

      <div v-if="!place" class="blood-empty">
        <EmptyState icon="location_on" :title="tr('Choose area', 'এলাকা বেছে নিন')">
          <Button icon="location_on" @click="picking = true">{{ tr('Choose area', 'এলাকা বেছে নিন')  }}</Button>
        </EmptyState>
      </div>
      <SkeletonCards v-else-if="nearby.isLoading && !others.length" :n="3" />
      <ErrorBox v-else-if="nearby.error" :error="tr('Couldn\'t load requests', 'রিকোয়েস্ট লোড হয়নি')" :on-retry="() => nearby.refetch()" />
      <div v-else-if="!others.length" class="blood-empty">
        <EmptyState
          icon="volunteer_activism"
          :title="tr('Good news!', 'সুখবর!')"
          :text="tr(`No requests within ${num(maxKm)} km right now.`, `এই মুহূর্তে ${num(maxKm)} কিমির মধ্যে কোনো রিকোয়েস্ট নেই।`)"
        >
          <Button icon="add" :style="{ marginTop: '16px' }" @click="newRequest">{{ tr('Create Request', 'রিকোয়েস্ট দিন') }}</Button>
        </EmptyState>
      </div>
      <template v-else>
        <RequestCard v-for="{ r, km } in others" :key="r.id" :r="r" :km="km" :selected="r.id === selectedId" @click="open(r.id)" />
      </template>

      <div v-if="others.length" class="blood-fab"><Fab icon="add" @click="newRequest">{{ tr('Request', 'রিকোয়েস্ট') }}</Fab></div>
      <PlacePicker v-if="picking" :busy="placeBusy" :error="placeErr" @picked="pickPlace" @close="picking = false" />
    </div>
    <template #detail>
      <RequestDetail v-if="selectedId" :key="selectedId" :id="selectedId" />
    </template>
    <template #placeholder><NeedNow /></template>
  </ListDetail>
</template>
