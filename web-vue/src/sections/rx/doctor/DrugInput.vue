<script setup lang="ts">
// ওষুধের নাম: লেখার সাথে সাথে তালিকা (drug_search); ↑↓ দিয়ে বাছা, Enter-এ নেওয়া, Esc-এ বন্ধ
import { ref, useId, watch } from 'vue';
import { cx } from '@/ui/kit';
import { call } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { Drug } from './model';

const props = defineProps<{ value: string; picked: boolean; label: string }>();
const emit = defineEmits<{ type: [v: string]; pick: [d: Drug] }>();
const id = 'd' + useId().replace(/[^a-zA-Z0-9-]/g, '');
const items = ref<Drug[]>([]);
const open = ref(false);
const hi = ref(0);
let seq = 0;
watch(
  () => [props.value, props.picked] as const,
  ([value, picked], _o, onCleanup) => {
    const q = value.trim();
    if (picked || q.length < 2) {
      seq++;
      items.value = [];
      open.value = false;
      return;
    }
    const n = ++seq;
    const t = setTimeout(async () => {
      try {
        const r = await call<{ items: Drug[] }>('drug_search', { q });
        if (n !== seq) return;
        const list = (r.items ?? []).slice(0, 8);
        items.value = list;
        hi.value = 0;
        open.value = list.length > 0 && document.activeElement?.id === id;
      } catch {
        /* খোঁজা না গেলেও লেখা যায় */
      }
    }, 250);
    onCleanup(() => clearTimeout(t));
  },
  { immediate: true },
);
function pick(d: Drug) {
  open.value = false;
  emit('pick', d);
}
function onKey(e: KeyboardEvent) {
  const list = items.value;
  if (!open.value || !list.length) {
    if (e.key === 'ArrowDown' && list.length) {
      e.preventDefault();
      open.value = true;
    }
    return;
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    hi.value = (hi.value + 1) % list.length;
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    hi.value = (hi.value - 1 + list.length) % list.length;
  } else if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
    e.preventDefault();
    pick(list[hi.value]);
  } else if (e.key === 'Escape') {
    e.preventDefault();
    e.stopPropagation();
    open.value = false;
  }
}
function onFocus() {
  if (items.value.length && !props.picked) open.value = true;
}
function onBlur() {
  setTimeout(() => (open.value = false), 120);
}
</script>

<template>
  <div class="rxd-combo">
    <label :for="id" class="rxd-mlabel">{{ tr('Medicine name', 'ওষুধের নাম') }}</label>
    <input
      :id="id"
      data-f="name"
      class="input rxd-in"
      role="combobox"
      :aria-label="label"
      aria-autocomplete="list"
      :aria-expanded="open"
      :aria-controls="id + 'l'"
      :aria-activedescendant="open && items[hi] ? `${id}o${hi}` : undefined"
      :value="value"
      :placeholder="tr('Type to search', 'লিখে খুঁজুন')"
      autocomplete="off"
      spellcheck="false"
      @input="emit('type', ($event.target as HTMLInputElement).value.slice(0, 100))"
      @focus="onFocus"
      @blur="onBlur"
      @keydown="onKey"
    />
    <ul v-if="open" :id="id + 'l'" role="listbox" class="rxd-pop" :aria-label="tr('Medicines', 'ওষুধ')">
      <li
        v-for="(d, i) in items"
        :id="`${id}o${i}`"
        :key="d.i + d.name"
        role="option"
        :aria-selected="i === hi"
        :class="cx('rxd-opt', i === hi && 'on')"
        @mousedown.prevent="pick(d)"
        @mouseenter="hi = i"
      >
        <span class="t-body-m semi">{{ d.name }}</span>
        <span class="t-body-s muted">{{ [d.generic, d.form, d.company].filter(Boolean).join(' · ') }}</span>
      </li>
    </ul>
  </div>
</template>
