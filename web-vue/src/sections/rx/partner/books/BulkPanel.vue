<script setup lang="ts">
// একসাথে অনেকগুলো নাম (প্রতি লাইনে একটা) — আগের স্টকের তালিকার মতো
import { computed, ref } from 'vue';
import { Button, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';

const props = defineProps<{ onAdded: () => void }>();
const text = ref('');
const busy = ref(false);
const lines = computed(() => [...new Set(text.value.split('\n').map((x) => x.trim()).filter(Boolean))]);
async function addAll() {
  busy.value = true;
  try {
    await call('stock_set', { add: lines.value });
    text.value = '';
    toast(tr('Added', 'যোগ হয়েছে'));
    props.onAdded();
  } catch (e) {
    toast(errorText(e));
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <details class="card rxp-panel rxp-bulk">
    <summary class="t-title-s">{{ tr('Add many at once', 'অনেকগুলো একসাথে যোগ করুন') }}</summary>
    <textarea v-model="text" class="textarea" rows="6" :aria-label="tr('One name per line', 'প্রতি লাইনে একটা নাম')" :placeholder="tr('One name per line', 'প্রতি লাইনে একটা নাম')" />
    <div class="row">
      <span class="t-body-s muted grow">{{ lines.length ? tr(`${lines.length} names`, `${num(lines.length)}টা নাম`) : '' }}</span>
      <Button variant="tonal" :busy="busy" :disabled="!lines.length" @click="addAll">{{ tr('Add all', 'সব যোগ করুন') }}</Button>
    </div>
  </details>
</template>
