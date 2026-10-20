<script setup lang="ts">
// /my-requests আর /my-requests/:id
import { computed } from 'vue';
import { Button, EmptyState, ErrorText, Fab, Loading, SkeletonCards } from '@/ui/kit';
import ListDetail from '@/shell/ListDetail.vue';
import SubPageBar from '@/shell/SubPageBar.vue';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { MAX_OPEN_REQUESTS } from '@/lib/rules';
import { canGoBack, useNav } from '@/lib/nav';
import { useMine } from './data';
import { isActive } from './model';
import RequestCard from './parts/RequestCard.vue';
import LoginNeeded from './LoginNeeded.vue';
import MyRequestDetail from './MyRequestDetail.vue';

defineProps<{ selectedId: string | null }>();
const s = useSession();
const nav = useNav();
const two = useTwoPane();
/** অ্যাপের ভেতরে আগের পাতা থাকলে সেখানে, নইলে প্রোফাইলে */
const back = () => (canGoBack() ? nav(-1) : nav('/profile'));
usePageTitle(() => tr('My Requests', 'আমার রিকোয়েস্ট'));
const mine = useMine();
const list = computed(() => [...(mine.data ?? [])].sort((a, b) => Number(isActive(b)) - Number(isActive(a)) || b.serverCreatedMillis - a.serverCreatedMillis));
const activeCount = computed(() => list.value.filter((r) => isActive(r)).length);
const canAdd = computed(() => !mine.isLoading && activeCount.value < MAX_OPEN_REQUESTS);
const newRequest = () => nav('/request/new');
</script>

<template>
  <Loading v-if="!s.ready" />
  <template v-else-if="s.guest">
    <SubPageBar :title="tr('My Requests', 'আমার রিকোয়েস্ট')" :on-back="back" />
    <LoginNeeded :reason="tr('Log in to see your requests', 'আপনার রিকোয়েস্ট দেখতে লগইন করুন')" />
  </template>
  <template v-else>
    <SubPageBar v-if="!selectedId || two" :title="tr('My Requests', 'আমার রিকোয়েস্ট')" :on-back="back" />
    <ListDetail :has-detail="!!selectedId">
      <div class="list-inner">
        <div v-if="canAdd" class="row blood-wide-only" :style="{ justifyContent: 'flex-end' }">
          <Button icon="add" square @click="newRequest">{{ tr('Request', 'রিকোয়েস্ট') }}</Button>
        </div>
        <p v-if="activeCount >= MAX_OPEN_REQUESTS" class="t-body-s muted">{{ tr(`You can keep up to ${num(MAX_OPEN_REQUESTS)} open requests.`, `একসাথে সর্বোচ্চ ${num(MAX_OPEN_REQUESTS)}টা খোলা রিকোয়েস্ট রাখা যায়।`) }}</p>
        <SkeletonCards v-if="mine.isLoading" :n="3" />
        <ErrorText v-else-if="mine.error" :text="tr('Couldn\'t load', 'লোড হয়নি')" />
        <EmptyState v-else-if="!list.length" icon="list_alt" :title="tr('No requests yet', 'এখনো কোনো রিকোয়েস্ট দেননি')" />
        <template v-else>
          <RequestCard v-for="r in list" :key="r.id" :r="r" :selected="r.id === selectedId" @click="nav(`/my-requests/${r.id}`)" />
        </template>
        <div v-if="canAdd" class="blood-fab"><Fab icon="add" @click="newRequest">{{ tr('Request', 'রিকোয়েস্ট') }}</Fab></div>
      </div>
      <template #detail><MyRequestDetail v-if="selectedId" :id="selectedId" :key="selectedId" /></template>
      <template #placeholder><EmptyState icon="list_alt" :title="tr('Choose a request', 'একটা রিকোয়েস্ট বেছে নিন')" /></template>
    </ListDetail>
  </template>
</template>
