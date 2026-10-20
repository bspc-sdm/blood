<script setup lang="ts">
// গোল ছবি বা আইকন (অ্যাপের CardAvatar)
import { computed } from 'vue';
import Icon from './Icon.vue';

const props = withDefaults(defineProps<{ photo?: string | null; icon?: string; size?: number; tint?: 'primary' | 'error' }>(), { icon: 'person_filled', size: 52 });
const src = computed(() => (props.photo ? (props.photo.startsWith('data:') ? props.photo : `data:image/jpeg;base64,${props.photo}`) : null));
</script>

<template>
  <span
    class="avatar"
    :style="{ width: size + 'px', height: size + 'px', ...(tint === 'error' ? { background: 'var(--error-a12)', color: 'var(--error)' } : {}) }"
  >
    <img v-if="src" :src="src" alt="" />
    <Icon v-else :name="icon" :size="size / 2" />
  </span>
</template>
