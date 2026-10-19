<script setup lang="ts">
// বড় পর্দায় কিছু বাছা না থাকলে: ছোট সারাংশ আর দরকারি এক-দুটো কাজ
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { Avatar, Icon } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { kindIcon, kindLabel, type Kind } from './data';

const props = defineProps<{ kind: Kind; area: string; items: any[]; busyCount: number; loading: boolean }>();
const stats = computed<[string, number][]>(() =>
  props.kind === 'ambulance'
    ? [[tr('Available', 'খালি আছে'), props.items.filter((a) => a.available).length], [tr('Call to check', 'ফোনে জেনে নিন'), props.items.filter((a) => !a.available).length], [tr('On a trip', 'ট্রিপে'), props.busyCount]]
    : props.kind === 'lab'
      ? [[tr('Labs', 'প্যাথলজি'), props.items.length], [tr('With prices', 'দাম দেওয়া'), props.items.filter((l) => l.prices).length], [tr('Sample from home', 'বাসা থেকে নমুনা নেয়'), props.items.filter((l) => l.homeCollection).length]]
      : [[tr('Open', 'খোলা'), props.items.filter((x) => x.openNow).length], [tr('Total', 'মোট'), props.items.length]],
);
</script>

<template>
  <div class="services-ph">
    <div class="row gap-12">
      <Avatar :icon="kindIcon(kind)" :tint="kind === 'bloodbank' ? 'error' : undefined" :size="48" />
      <div class="col gap-2 grow">
        <h2 class="t-title-l">{{ kindLabel(kind) }}</h2>
        <span v-if="area" class="t-body-m muted ellipsis">{{ area }}</span>
      </div>
    </div>
    <div v-if="area && !loading" class="services-stats">
      <div v-for="[l, v] in stats" :key="l" class="stat">
        <span class="v">{{ num(v) }}</span>
        <span class="l">{{ l }}</span>
      </div>
    </div>
    <p class="t-body-m muted">{{ tr('Pick one from the list to see details.', 'বিস্তারিত দেখতে তালিকা থেকে একটা বেছে নিন।') }}</p>
    <div class="row wrap">
      <a v-if="kind === 'ambulance'" class="btn danger square has-icon" href="tel:999"><Icon name="call" />{{ tr('Call 999', '৯৯৯-এ ফোন করুন') }}</a>
      <RouterLink v-if="kind === 'pharmacy'" class="btn tonal square has-icon" to="/services/pharmacy/medicine"><Icon name="search" />{{ tr('Find a medicine', 'ওষুধ খুঁজুন') }}</RouterLink>
      <RouterLink v-if="kind === 'lab'" class="btn tonal square has-icon" to="/prescriptions"><Icon name="list_alt" />{{ tr('My prescriptions', 'আমার প্রেসক্রিপশন') }}</RouterLink>
    </div>
  </div>
</template>
