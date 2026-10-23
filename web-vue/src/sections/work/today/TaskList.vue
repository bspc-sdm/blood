<script setup lang="ts">
// "এখনই দেখুন": যে কাজগুলো এখন করতে হবে — সবচেয়ে নতুন আগে, ৫টা পর্যন্ত (বাকিগুলো "সব দেখুন")। প্রতিটা সারিতে ভূমিকার নাম,
// এক লাইনের শিরোনাম, নিচে সময়/রোগী/কোড, আর একটাই বোতাম (গ্রহণ করুন / তৈরি / রিপোর্ট পাঠান / ডাকুন / স্টক দিন …)।
// সরিয়ে দেওয়া যায় না — কাজ শেষ হলে নিজেই চলে যায়।
import { computed, ref } from 'vue';
import { Button, Icon } from '@/ui/kit';
import { timeAgo } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { TT } from './text';
import { roleIcon, roleLabel, sortTasks, type TodayTask } from './model';

const props = defineProps<{ tasks: TodayTask[]; showRole: boolean }>();
const nav = useNav();
const all = ref(false);
const sorted = computed(() => sortTasks(props.tasks));
const shown = computed(() => (all.value ? sorted.value : sorted.value.slice(0, 5)));
const subOf = (t: TodayTask) => [t.sub, t.at ? timeAgo(t.at) : ''].filter(Boolean).join(' · ');
</script>

<template>
  <section class="td-tasks" data-testid="today-tasks">
    <h2 class="t-title-m">{{ TT.now() }}</h2>
    <p v-if="!tasks.length" class="td-none" data-testid="no-tasks"><Icon name="check_circle" />{{ TT.nothing() }}</p>
    <ul v-else class="td-task-list">
      <li v-for="t in shown" :key="t.kind + t.id" class="td-task" :data-kind="t.kind">
        <span class="td-task-ic" aria-hidden="true"><Icon :name="roleIcon(t.kind)" /></span>
        <span class="col grow" :style="{ minWidth: 0 }">
          <span v-if="showRole" class="td-task-role">{{ roleLabel(t.kind) }}</span>
          <span class="td-task-title">{{ t.title }}</span>
          <span v-if="subOf(t)" class="t-body-s muted td-task-sub">{{ subOf(t) }}</span>
        </span>
        <Button small :variant="t.action === 'open' || !t.action ? 'tonal' : 'filled'" @click="nav(t.link || '/work')">{{ TT.act(t.action) }}</Button>
      </li>
    </ul>
    <Button v-if="tasks.length > 5 && !all" variant="text" small data-testid="tasks-all" @click="all = true">{{ TT.seeAll(tasks.length) }}</Button>
  </section>
</template>
