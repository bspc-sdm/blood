<script setup lang="ts">
// সরকারি তালিকা থেকে খোঁজা: "যোগ" = শুধু নাম (আগের মতো), "দাম সহ" = পুরো ফর্ম
import { computed, ref, watch } from 'vue';
import { Button, Icon, Loading, Tag, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr, trPlain } from '@/lib/i18n';
import type { Drug } from '../model';
import Panel from '../Panel.vue';
import type { Draft } from './types';

const props = defineProps<{ have: Set<string>; onAdded: () => void }>();
const emit = defineEmits<{ pick: [d: Draft] }>();
const q = ref('');
const found = ref<Drug[]>([]);
const loading = ref(false);
const busy = ref<string | null>(null);
const input = ref<HTMLInputElement | null>(null);
const term = computed(() => q.value.trim());
const k = (s: string) => s.trim().toLowerCase().replace(/\s+/g, ' ');

watch(
  term,
  (t, _o, onCleanup) => {
    if (t.length < 2) {
      found.value = [];
      loading.value = false;
      return;
    }
    let live = true;
    loading.value = true;
    const tm = setTimeout(async () => {
      try {
        const r = await call<{ items: Drug[] }>('drug_search', { q: t });
        if (live) found.value = (r.items ?? []).slice(0, 8);
      } catch {
        if (live) found.value = [];
      } finally {
        if (live) loading.value = false;
      }
    }, 300);
    onCleanup(() => {
      live = false;
      clearTimeout(tm);
    });
  },
  { immediate: true },
);

async function quick(d: { name: string; generic?: string; form?: string }) {
  busy.value = d.name;
  try {
    await call('books_item_save', { name: d.name, generic: d.generic ?? '', form: d.form ?? '' });
    toast(trPlain(`${d.name} — added`, `${d.name} — যোগ হয়েছে`));
    props.onAdded();
    input.value?.focus();
  } catch (e) {
    toast(errorText(e));
  } finally {
    busy.value = null;
  }
}
const exact = computed(() => found.value.some((d) => k(d.name) === k(term.value)));
</script>

<template>
  <Panel :title="tr('Add a product', 'পণ্য যোগ করুন')" icon="add">
    <div class="rxp-search">
      <Icon name="search" />
      <input
        ref="input"
        class="input"
        :value="q"
        maxlength="60"
        role="combobox"
        :aria-expanded="found.length > 0"
        :placeholder="tr('Type the medicine name', 'ওষুধের নাম লিখুন')"
        :aria-label="tr('Add a product', 'পণ্য যোগ করুন')"
        @input="q = ($event.target as HTMLInputElement).value"
      />
    </div>
    <template v-if="term.length >= 2">
      <Loading v-if="loading && !found.length" />
      <ul v-else class="rxp-found">
        <li v-for="d in found" :key="d.i">
          <div class="col gap-2 grow">
            <span class="semi">{{ d.name }}</span>
            <span class="t-body-s muted">{{ [d.generic, d.form, d.company].filter(Boolean).join(' · ') }}</span>
          </div>
          <Tag v-if="have.has(k(d.name))" tone="good">{{ tr('Added', 'আছে') }}</Tag>
          <div v-else class="row gap-4 none">
            <Button small variant="text" @click="emit('pick', { name: d.name, generic: d.generic, form: d.form })">{{ tr('With price', 'দাম সহ') }}</Button>
            <Button small icon="add" :busy="busy === d.name" :disabled="!!busy" @click="quick(d)">{{ tr('Add', 'যোগ') }}</Button>
          </div>
        </li>
        <li v-if="!exact && !loading && !have.has(k(term))" class="rxp-found-free">
          <span class="grow t-body-s">{{ found.length ? tr('Not in the list?', 'তালিকায় নেই?') : tr('No match in the list', 'তালিকায় মেলেনি') }}</span>
          <Button small variant="outline" icon="add" @click="emit('pick', { name: term })">{{ tr(`Add "${term}"`, `"${term}" যোগ করুন`) }}</Button>
        </li>
      </ul>
    </template>
  </Panel>
</template>
