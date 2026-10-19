<script setup lang="ts">
// প্রেসক্রিপশনের কোড লেখা/পেস্ট: Enter-এ খোলে; লিংক পেস্ট করলেও কোডটা বের করে নেয়
import { ref } from 'vue';
import { Button, ErrorText, cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { cleanCode, validCode } from './model';

defineProps<{ label: string; placeholder?: string; busy?: boolean; autoFocus?: boolean; error?: string | null }>();
const emit = defineEmits<{ open: [code: string] }>();
const code = ref('');
const err = ref<string | null>(null);
const input = ref<HTMLInputElement | null>(null);
function submit(raw = code.value) {
  const c = cleanCode(raw);
  if (!validCode(c)) {
    err.value = tr('Check the code on the prescription', 'প্রেসক্রিপশনের কোডটা দেখে লিখুন');
    input.value?.focus();
    return;
  }
  err.value = null;
  emit('open', c);
}
function onInput(e: Event) {
  const t = e.target as HTMLInputElement;
  err.value = null;
  code.value = cleanCode(t.value);
  if (t.value !== code.value) t.value = code.value;
}
function onPaste(e: ClipboardEvent) {
  const t = e.clipboardData?.getData('text') ?? '';
  const c = cleanCode(t);
  if (validCode(c)) {
    e.preventDefault();
    code.value = c;
    submit(c);
  }
}
defineExpose({ focus: () => input.value?.focus(), input });
</script>

<template>
  <form class="col gap-6" @submit.prevent="submit()">
    <label class="t-label-l" for="rxp-code">{{ label }}</label>
    <div class="rxp-code-row">
      <input
        id="rxp-code"
        ref="input"
        v-focus="!!autoFocus"
        :class="cx('input rxp-code-input', (err || error) && 'bad')"
        :value="code"
        autocomplete="off"
        spellcheck="false"
        inputmode="text"
        maxlength="80"
        :placeholder="placeholder ?? tr('Code under the QR', 'QR-এর নিচের কোড')"
        @input="onInput"
        @paste="onPaste"
      />
      <Button type="submit" :busy="busy" icon="search">{{ tr('Open', 'খুলুন') }}</Button>
    </div>
    <ErrorText :text="err ?? error" />
  </form>
</template>
