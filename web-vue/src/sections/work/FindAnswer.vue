<script setup lang="ts">
// দোকানের উত্তর একটা খোঁজে (backend/docs/MEDFIND.md): আছে হলে কতটা আছে লিখে পাঠান (ক্রেতার এককে) — আবার পাঠালে
// সংখ্যা বদলায়। "নেই" বোতাম নেই: না থাকলে কিছু না করলেই চলে, বা চুপচাপ "বাদ দিন" (শুধু নিজের তালিকা থেকে সরে, ক্রেতা কিছু জানেন না)।
import { computed, ref } from 'vue';
import { Button } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { HAVE_MAX, parseQty, qtyText, unitWord } from '@/sections/services/medfind';

const props = defineProps<{ unit: string; haveQty: number | null; busy: boolean; disabled: boolean }>();
const emit = defineEmits<{ answer: [haveQty: number]; dismiss: [] }>();

const editing = ref(false);
const text = ref('');
const err = ref<string | null>(null);
const asking = computed(() => props.haveQty == null || editing.value);

// নতুন লেখা (React-এ ছিল না)
const L = computed(() => ({
  howMany: tr('How many do you have', 'কতটা আছে'), // bn-ok
  send: tr('Send', 'পাঠান'), // bn-ok
  change: tr('Change', 'বদলান'),
  dismiss: tr('Dismiss', 'বাদ দিন'), // bn-ok
  bad: tr(`Write a number from 1 to ${HAVE_MAX}`, '১ থেকে ৯৯৯৯-এর মধ্যে একটা সংখ্যা লিখুন'), // bn-ok
}));
const said = computed(() => (props.haveQty ? tr(`You said: have ${qtyText(props.haveQty, props.unit)}`, `আপনার উত্তর: আছে — ${qtyText(props.haveQty, props.unit)}`) : '')); // bn-ok

function edit() {
  text.value = props.haveQty ? String(props.haveQty) : '';
  err.value = null;
  editing.value = true;
}
function send() {
  const n = parseQty(text.value, HAVE_MAX);
  if (n == null) {
    err.value = L.value.bad;
    return;
  }
  err.value = null;
  editing.value = false;
  emit('answer', n);
}
</script>

<template>
  <div class="col gap-6" data-testid="find-answer">
    <div v-if="!asking" class="row wrap gap-8">
      <span class="t-label-l c-primary" data-testid="find-said">{{ said }}</span>
      <Button variant="text" small :disabled="disabled" @click="edit">{{ L.change }}</Button>
    </div>
    <template v-else>
      <form class="medfind-some" @submit.prevent="send">
        <label class="medfind-some-label">
          <span class="t-body-m">{{ L.howMany }}</span>
          <span class="medfind-some-field">
            <input v-model="text" class="input medfind-some-input" inputmode="numeric" maxlength="4" autocomplete="off" :aria-label="L.howMany" data-testid="find-have-qty" />
            <span class="t-body-m semi" data-testid="find-have-unit">{{ unitWord(unit) }}</span>
          </span>
        </label>
        <Button type="submit" small icon="send" :busy="busy" :disabled="disabled" data-testid="find-have-send">{{ L.send }}</Button>
        <Button v-if="haveQty == null" variant="text" small class="medfind-dismiss" :disabled="disabled" data-testid="find-dismiss" @click="emit('dismiss')">{{ L.dismiss }}</Button>
        <Button v-else variant="text" small :disabled="disabled" @click="editing = false">{{ tr('Cancel', 'বাতিল') }}</Button>
      </form>
      <span v-if="err" class="t-body-s c-error">{{ err }}</span>
    </template>
  </div>
</template>
