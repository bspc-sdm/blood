<script setup lang="ts">
// ছক থেকে পরীক্ষা খোঁজা (নাম, বাংলা নাম, ডাক্তারের লেখা অন্য নাম) — না পেলে নিজে লিখে যোগ
import { computed, ref } from 'vue';
import { Button, Icon } from '@/ui/kit';
import { isBangla, tr } from '@/lib/i18n';
import Panel from './Panel.vue';
import type { TestDef } from './model';
import { norm } from './labprices';

const props = defineProps<{ tests: TestDef[]; has: (code: string | null, name: string) => boolean; suggest: boolean }>();
const emit = defineEmits<{ add: [code: string | null, name: string] }>();
const q = ref('');
const input = ref<HTMLInputElement | null>(null);
const t = computed(() => norm(q.value));
// মোবাইলে লিখলে তবেই মিল দেখায় (তালিকা নিচে চাপা না পড়ে)
const found = computed(() =>
  (t.value ? props.tests.filter((x) => [x.name, x.bn ?? '', x.code, ...(x.aliases ?? [])].some((s) => norm(s).includes(t.value))) : props.suggest ? props.tests : [])
    .filter((x) => !props.has(x.code, x.name))
    .slice(0, t.value ? 8 : 6),
);
const exact = computed(() => props.tests.some((x) => [x.name, ...(x.aliases ?? [])].some((s) => norm(s) === t.value)));
function pick(code: string | null, name: string) {
  emit('add', code, name);
  q.value = '';
  input.value?.focus();
}
function onKey(e: KeyboardEvent) {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  if (found.value[0]) pick(found.value[0].code, found.value[0].name);
  else if (q.value.trim().length >= 2) pick(null, q.value.trim());
}
</script>

<template>
  <Panel :title="tr('Add a test', 'পরীক্ষা যোগ করুন')" icon="add">
    <div class="rxp-search">
      <Icon name="search" />
      <input ref="input" v-model="q" class="input" maxlength="80" :aria-label="tr('Find a test', 'পরীক্ষা খুঁজুন')" :placeholder="tr('Find a test', 'পরীক্ষা খুঁজুন')" @keydown="onKey" />
    </div>
    <ul class="rxp-found">
      <li v-for="x in found" :key="x.code">
        <span class="col gap-2 grow">
          <span class="semi">{{ x.name }}</span>
          <span v-if="isBangla() && x.bn" class="t-body-s muted">{{ x.bn }}</span>
        </span>
        <Button small variant="tonal" icon="add" @click="pick(x.code, x.name)">{{ tr('Add', 'যোগ') }}</Button>
      </li>
      <li v-if="q.trim().length >= 2 && !exact && !has(null, q.trim())" class="rxp-found-free">
        <span class="t-body-s muted grow">{{ tr('Not in the list?', 'তালিকায় নেই?') }}</span>
        <Button small variant="outline" icon="add" @click="pick(null, q.trim())">{{ tr(`Add "${q.trim()}"`, `"${q.trim()}" যোগ করুন`) }}</Button>
      </li>
    </ul>
  </Panel>
</template>
