<script setup lang="ts">
// হেলথ টিপস — অ্যাপের HealthTipsTab, TipDetailScreen (শুধু অনুমোদিত ব্যাজধারী টিপস দেন)
import { computed, watch } from 'vue';
import { Button, EmptyState, ErrorBox, Fab, Loading, SkeletonCards } from '@/ui/kit';
import ListDetail from '@/shell/ListDetail.vue';
import DetailPane from '@/shell/DetailPane.vue';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { approvedBadge, useMe } from './common';
import { usePaged, type Tip } from './paged';
import HealthTabs from './HealthTabs.vue';
import ListHeading from './ListHeading.vue';
import TipCard from './TipCard.vue';
import TipDetail from './TipDetail.vue';

const props = defineProps<{ id: string | null }>();
usePageTitle(() => tr('Health', 'স্বাস্থ্য'));
const nav = useNav();
const me = useMe();
const two = useTwoPane();
const p = usePaged<Tip>('tips');
const canPost = computed(() => !!approvedBadge(me.value));
const tip = computed(() => (props.id ? p.items.find((t) => t.id === props.id) ?? null : null));

// সরাসরি লিংকে খোলা টিপস প্রথম পাতায় না থাকলে পরের পাতাগুলো আনা (সর্বোচ্চ ১০ পাতা)
const pages = computed(() => p.q.data?.pages.length ?? 0);
watch(
  () => [props.id, tip.value, p.q.hasNextPage, p.q.isFetchingNextPage, pages.value] as const,
  () => {
    if (props.id && !tip.value && p.q.hasNextPage && !p.q.isFetchingNextPage && pages.value < 10) p.q.fetchNextPage();
  },
  { immediate: true },
);
const searching = computed(() => p.q.isPending || p.q.isFetchingNextPage || (p.q.hasNextPage && pages.value < 10));
</script>

<template>
  <ListDetail :has-detail="!!id">
    <div class="list-inner">
      <HealthTabs value="tips" />
      <ListHeading :title="tr('Health Tips', 'হেলথ টিপস')" :on-refresh="() => p.q.refetch()" :refreshing="p.q.isFetching && !p.q.isFetchingNextPage">
        <template v-if="two && canPost" #action><Button small icon="edit_note" @click="nav('/health/post-tip')">{{ tr('Post a tip', 'টিপস দিন') }}</Button></template>
      </ListHeading>
      <SkeletonCards v-if="p.q.isPending" :n="4" :h="130" />
      <ErrorBox v-else-if="p.q.isError && !p.items.length" :error="errorText(p.q.error)" :on-retry="() => p.q.refetch()" />
      <EmptyState v-else-if="!p.items.length" icon="lightbulb_outline" :title="tr('No tips yet', 'এখনো কোনো টিপস নেই')" :text="tr('Doctors, nurses and health experts will share tips here.', 'ডাক্তার, নার্স আর স্বাস্থ্য বিশেষজ্ঞরা এখানে টিপস দেবেন।')" />
      <template v-else>
        <TipCard v-for="t in p.items" :key="t.id" :t="t" :selected="t.id === id" @click="nav(`/health/tip/${t.id}`)" />
        <Button v-if="p.q.hasNextPage" variant="text" block :busy="p.q.isFetchingNextPage" @click="p.q.fetchNextPage()">{{ tr('Load more', 'আরও দেখুন') }}</Button>
      </template>
      <Fab v-if="!two && canPost" icon="edit_note" @click="nav('/health/post-tip')">{{ tr('Tip', 'টিপস') }}</Fab>
    </div>
    <template #detail>
      <template v-if="id">
        <TipDetail v-if="tip" :key="tip.id" :tip="tip" />
        <DetailPane v-else :title="tr('Health Tips', 'হেলথ টিপস')" :on-back="() => nav('/health/tips')">
          <Loading v-if="searching" />
          <ErrorBox v-else-if="p.q.isError" :error="errorText(p.q.error)" :on-retry="() => p.q.refetch()" />
          <EmptyState v-else icon="lightbulb_outline" :title="tr('Not found', 'পাওয়া যায়নি')" />
        </DetailPane>
      </template>
    </template>
    <template #placeholder><EmptyState icon="lightbulb_outline" :title="tr('Pick a tip', 'একটা টিপস বেছে নিন')" /></template>
  </ListDetail>
</template>
