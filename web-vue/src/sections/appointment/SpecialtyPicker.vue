<script setup lang="ts">
// বিশেষজ্ঞতা: তালিকায় যেগুলো আছে (সংখ্যাসহ), বেশি হলে খোঁজার ঘর
import { computed, ref } from 'vue';
import { ChoiceChips } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { specialtyLabel } from './model';

const props = defineProps<{ options: string[]; count: (c: string) => number | undefined; value: string | null }>();
const emit = defineEmits<{ change: [v: string | null] }>();
const q = ref('');
const list = computed(() => {
  const t = q.value.trim().toLowerCase();
  return (props.value && !props.options.includes(props.value) ? [props.value, ...props.options] : props.options).filter((c) => !t || specialtyLabel(c).toLowerCase().includes(t) || c.includes(t));
});
</script>

<template>
  <div class="col gap-6">
    <span class="t-title-s">{{ tr('Specialty', 'বিশেষজ্ঞতা') }}</span>
    <input
      v-if="options.length > 8"
      class="input"
      :value="q"
      :placeholder="tr('Search specialty', 'বিশেষজ্ঞতা খুঁজুন')"
      :aria-label="tr('Search specialty', 'বিশেষজ্ঞতা খুঁজুন')"
      @input="q = ($event.target as HTMLInputElement).value.slice(0, 40)"
    />
    <ChoiceChips
      v-if="list.length"
      :options="list.map((c) => ({ value: c, label: specialtyLabel(c) + (count(c) ? ` (${num(count(c)!)})` : '') }))"
      :model-value="value"
      @update:model-value="(v: string) => emit('change', v === props.value ? null : v)"
    />
    <span v-else class="t-body-s muted">{{ tr('No match', 'মেলেনি') }}</span>
  </div>
</template>
