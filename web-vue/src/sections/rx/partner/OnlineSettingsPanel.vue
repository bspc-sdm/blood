<script setup lang="ts">
// অ্যাপের OnlineSettingsDialog: চালু/বন্ধ, কত কিমি, ডেলিভারি চার্জ, নোট
import { ErrorText, Loading, Tag } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import type { PharmacyHome } from './model';
import Panel from './Panel.vue';
import OnlineForm from './OnlineForm.vue';

const home = useCall<PharmacyHome>('rx_pharmacy_home', {});
</script>

<template>
  <Panel :title="tr('Selling online', 'অনলাইনে বিক্রি')" icon="local_shipping">
    <template v-if="home.data" #actions>
      <Tag :tone="home.data.online.enabled ? 'good' : undefined">{{ home.data.online.enabled ? tr('Selling online', 'অনলাইনে বিক্রি চালু') : tr('In-shop only', 'শুধু দোকানে') }}</Tag>
    </template>
    <OnlineForm v-if="home.data" :key="JSON.stringify(home.data.online)" :start="home.data.online" :has-location="home.data.hasLocation" />
    <ErrorText v-else-if="home.error" :text="errorText(home.error)" />
    <Loading v-else />
  </Panel>
</template>
