<script setup lang="ts">
// ছাড় ও বাসার চার্জ (LabPricesEditor-এর অংশ)
import { RouterLink } from 'vue-router';
import { TextInput } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { taka } from '@/lib/labprice';
import Panel from './Panel.vue';
import { parseNum } from './model';

defineProps<{ disc: string; fee: string; data: { homeCollection: boolean } }>();
const emit = defineEmits<{ disc: [v: string]; fee: [v: string] }>();
const setDisc = (v: string) => emit('disc', v);
const setFee = (v: string) => emit('fee', v);
</script>

<template>
<Panel :title="tr('Discount & home fee', 'ছাড় ও বাসার চার্জ')" icon="tune">
  <TextInput :label="tr('Discount on all tests (%)', 'সব পরীক্ষায় ছাড় (%)')" inputmode="numeric" maxlength="2" :model-value="disc" placeholder="০" @update:model-value="setDisc" />
  <TextInput v-if="data.homeCollection" :label="tr('Sample from home — fee (Tk)', 'বাসা থেকে নমুনা — চার্জ (৳)')" inputmode="numeric" maxlength="5" :model-value="fee" placeholder="৳" @update:model-value="setFee" />
  <div v-else class="row wrap gap-8">
    <span class="t-body-s muted grow">{{ tr('Sample from home is off', 'বাসা থেকে নমুনা বন্ধ') }}</span>
    <RouterLink to="/work/lab/edit" class="btn text small">{{ tr('Turn on', 'চালু করুন') }}</RouterLink>
  </div>
  <span v-if="data.homeCollection && parseNum(fee) === 0" class="t-body-s muted">{{ tr('Patients see: free', 'রোগীরা দেখবেন: ফ্রি') }}</span>
  <span v-if="parseNum(disc)" class="t-body-s muted">{{ tr(`e.g. ${taka(1000)} → ${taka(1000 - 10 * (parseNum(disc) ?? 0))}`, `যেমন ${taka(1000)} → ${taka(1000 - 10 * (parseNum(disc) ?? 0))}`) }}</span>
</Panel>
</template>
