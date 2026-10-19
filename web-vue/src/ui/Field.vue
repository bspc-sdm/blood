<script setup lang="ts">
defineProps<{ label?: string | null; hint?: string | null; error?: string | null; htmlFor?: string }>();
</script>

<template>
  <div class="field">
    <label v-if="label || $slots.label" :for="htmlFor"><slot name="label">{{ label }}</slot></label>
    <slot />
    <span v-if="error" class="err">{{ error }}</span>
    <span v-else-if="hint || $slots.hint" class="hint"><slot name="hint">{{ hint }}</slot></span>
  </div>
</template>
