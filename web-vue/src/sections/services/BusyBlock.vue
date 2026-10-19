<script setup lang="ts">
// ট্রিপে থাকা গাড়ি: আলাদা ভাঁজ করা অংশ; খালি গাড়ি না থাকলে নিজে থেকে খোলা
import { Icon } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';

defineProps<{ open: boolean; count: number }>();
const emit = defineEmits<{ toggle: [] }>();
</script>

<template>
  <div class="col gap-12">
    <button type="button" class="services-fold" :aria-expanded="open" @click="emit('toggle')">
      <span class="t-title-s grow">{{ tr(`On a trip now (${count})`, `এখন ট্রিপে আছে (${num(count)})`) }}</span>
      <Icon name="expand_more_round" :class="open ? 'flip' : undefined" />
    </button>
    <template v-if="open">
      <p class="t-body-s muted">{{ tr("They're busy — call only if you can wait, to ask when they'll be free.", 'এরা এখন ব্যস্ত — অপেক্ষা করতে পারলে ফোন করে জেনে নিন কখন খালি হবে।') }}</p>
      <slot />
    </template>
  </div>
</template>
