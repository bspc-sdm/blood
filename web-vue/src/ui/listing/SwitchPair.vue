<script setup lang="ts">
// এক চাপের সুইচ ("আমার কাজ"): দুটো (বা কয়েকটা) পাশাপাশি বোতাম, এখনকারটা বাছা — অন্যটায় চাপলেই বদলায়
import { Icon } from '@/ui/kit';

export interface SwitchOpt { key: string; label: string; icon?: string; good?: boolean }
defineProps<{ options: SwitchOpt[]; value: string | null; busy?: boolean; label: string; testid?: string }>();
const emit = defineEmits<{ pick: [key: string] }>();
</script>

<template>
  <div class="row wrap gap-6" role="group" :aria-label="label" :data-testid="testid">
    <button
      v-for="o in options"
      :key="o.key"
      type="button"
      :class="['pv-switch', o.good && 'good']"
      :aria-pressed="value === o.key"
      :disabled="busy"
      :data-key="o.key"
      @click="value !== o.key && emit('pick', o.key)"
    >
      <Icon v-if="value === o.key" name="check_circle" /><Icon v-else-if="o.icon" :name="o.icon" />{{ o.label }}
    </button>
  </div>
</template>
