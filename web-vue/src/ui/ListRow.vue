<script setup lang="ts">
import Icon from './Icon.vue';

defineProps<{ icon?: string; title?: string | null; sub?: string | null; onClick?: () => void; href?: string; danger?: boolean }>();
</script>

<template>
  <component :is="href ? 'a' : 'button'" class="list-row" v-bind="href ? { href } : { type: 'button', disabled: !onClick }" @click="!href && onClick?.()">
    <Icon v-if="icon" :name="icon" :style="danger ? { color: 'var(--error)' } : undefined" />
    <span class="col gap-2 grow">
      <span class="t-body-l" :style="danger ? { color: 'var(--error)' } : undefined"><slot name="title">{{ title }}</slot></span>
      <span v-if="sub || $slots.sub" class="sub"><slot name="sub">{{ sub }}</slot></span>
    </span>
    <slot v-if="$slots.trailing" name="trailing" />
    <Icon v-else-if="onClick || href" name="chevron_right" class="chev" />
  </component>
</template>
