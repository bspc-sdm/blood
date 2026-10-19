<script setup lang="ts">
// স্বাস্থ্য আলোচনা — অ্যাপের HealthDiscussionTab
import { Button, EmptyState, ErrorBox, Fab, SkeletonCards } from '@/ui/kit';
import ListDetail from '@/shell/ListDetail.vue';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { usePaged, type Question } from './paged';
import HealthTabs from './HealthTabs.vue';
import ListHeading from './ListHeading.vue';
import EmergencyNotice from './EmergencyNotice.vue';
import QuestionCard from './QuestionCard.vue';
import QuestionDetail from './QuestionDetail.vue';

defineProps<{ id: string | null }>();
usePageTitle(() => tr('Health', 'স্বাস্থ্য'));
const nav = useNav();
const s = useSession();
const two = useTwoPane();
const p = usePaged<Question>('questions');
const ask = () => s.requireLogin(tr('Log in to ask a question', 'প্রশ্ন করতে লগইন করুন'), () => nav('/health/ask'));
</script>

<template>
  <ListDetail :has-detail="!!id">
    <div class="list-inner">
      <HealthTabs value="discussion" />
      <ListHeading :title="tr('Health Discussion', 'স্বাস্থ্য আলোচনা')" :on-refresh="() => p.q.refetch()" :refreshing="p.q.isFetching && !p.q.isFetchingNextPage">
        <template v-if="two" #action><Button small icon="edit_note" @click="ask">{{ tr('Ask a question', 'প্রশ্ন করুন') }}</Button></template>
      </ListHeading>
      <EmergencyNotice />
      <SkeletonCards v-if="p.q.isPending" :n="4" :h="120" />
      <ErrorBox v-else-if="p.q.isError && !p.items.length" :error="errorText(p.q.error)" :on-retry="() => p.q.refetch()" />
      <EmptyState v-else-if="!p.items.length" icon="forum_outline" :title="tr('No questions yet', 'এখনো কোনো প্রশ্ন নেই')" :text="tr('Ask a health question and verified doctors will answer.', 'স্বাস্থ্য নিয়ে প্রশ্ন করুন, যাচাই করা ডাক্তাররা উত্তর দেবেন।')" />
      <template v-else>
        <QuestionCard v-for="it in p.items" :key="it.id" :q="it" :selected="it.id === id" @click="nav(`/health/question/${it.id}`)" />
        <Button v-if="p.q.hasNextPage" variant="text" block :busy="p.q.isFetchingNextPage" @click="p.q.fetchNextPage()">{{ tr('Load more', 'আরও দেখুন') }}</Button>
      </template>
      <Fab v-if="!two" icon="edit_note" @click="ask">{{ tr('Ask a question', 'প্রশ্ন করুন') }}</Fab>
    </div>
    <template #detail><QuestionDetail v-if="id" :id="id" :key="id" /></template>
    <template #placeholder><EmptyState icon="forum_outline" :title="tr('Pick a question', 'একটা প্রশ্ন বেছে নিন')" /></template>
  </ListDetail>
</template>
