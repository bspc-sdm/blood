<script setup lang="ts">
import Icon from './Icon.vue';

type BtnVariant = 'filled' | 'tonal' | 'outline' | 'text' | 'danger' | 'danger-text';
withDefaults(defineProps<{ variant?: BtnVariant; icon?: string; busy?: boolean; block?: boolean; small?: boolean; square?: boolean; disabled?: boolean; type?: 'button' | 'submit' | 'reset' }>(), {
  variant: 'filled',
  type: 'button',
});
</script>

<template>
  <button
    :type="type"
    :disabled="disabled || busy"
    :class="['btn', variant !== 'filled' && variant, icon && 'has-icon', block && 'block', small && 'small', square && 'square']"
  >
    <span v-if="busy" class="spinner small" />
    <Icon v-else-if="icon" :name="icon" />
    <slot />
  </button>
</template>
