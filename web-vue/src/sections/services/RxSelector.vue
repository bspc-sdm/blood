<script setup lang="ts">
// "প্রেসক্রিপশন: ডা. … · তারিখ ▾" — চাপলে তালিকা; "প্রেসক্রিপশন ছাড়া" বাছলে সাধারণ তালিকা
import { ref } from 'vue';
import { Dialog, Icon, ListRow } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { Rx } from '@/sections/health/rx';
import { rxLabel } from './pharmacy';

defineProps<{ list: Rx[]; value: Rx | null }>();
const emit = defineEmits<{ change: [id: string] }>();
const open = ref(false);
function pick(id: string) {
  emit('change', id);
  open.value = false;
}
</script>

<template>
  <button type="button" class="ph-rxsel" data-testid="ph-rx-select" @click="open = true">
    <Icon name="medication" />
    <span class="grow ellipsis">{{ value ? tr('Prescription: ', 'প্রেসক্রিপশন: ') + rxLabel(value) : tr('Without a prescription', 'প্রেসক্রিপশন ছাড়া') }}</span>
    <Icon name="expand_more_round" />
  </button>
  <Dialog v-if="open" size="small" :title="tr('Which prescription?', 'কোন প্রেসক্রিপশন?')" @close="open = false">
    <p class="t-body-s muted">{{ tr('Each pharmacy shows how many of its medicines it has and the likely price.', 'প্রতিটা ফার্মেসিতে এর কত ওষুধ আছে আর আনুমানিক দাম দেখাবে।') }}</p>
    <div class="col gap-4">
      <ListRow v-for="r in list" :key="r.id" icon="medication" :title="rxLabel(r)" :sub="(r.medicines ?? []).map((m) => m.name).join(', ')" @click="pick(r.id)">
        <template v-if="value?.id === r.id" #trailing><Icon name="check_circle" class="c-primary" /></template>
      </ListRow>
      <ListRow icon="storefront" :title="tr('Without a prescription', 'প্রেসক্রিপশন ছাড়া')" @click="pick('none')">
        <template v-if="!value" #trailing><Icon name="check_circle" class="c-primary" /></template>
      </ListRow>
    </div>
  </Dialog>
</template>
