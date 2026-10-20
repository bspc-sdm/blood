<script setup lang="ts">
import { ref } from 'vue';
import { Button, Icon, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { payLabel, type ReviewSettings } from './model';
import ReviewSettingsDialog from './ReviewSettingsDialog.vue';

const q = useCall<ReviewSettings>('review_settings');
const open = ref(false);
function settingsLine(s: ReviewSettings) {
  if (!s.enabled) return tr('Off', 'বন্ধ');
  if (s.noFee) return tr('On · No fee', 'চালু · ফি চাই না');
  return [tr('On', 'চালু'), s.fee, s.payMethods.map(payLabel).join(', '), num(s.payNumber)].filter(Boolean).join(' · ');
}
function saved() {
  open.value = false;
  toast(tr('Saved', 'সেভ হয়েছে'));
  q.refetch();
}
</script>

<template>
  <section class="card row wrap gap-12 rxd-settings">
    <span class="rxd-ic"><Icon name="settings_outline" /></span>
    <div class="col gap-2 grow">
      <span class="t-title-m">{{ tr('Online report review', 'অনলাইনে রিপোর্ট দেখা') }}</span>
      <span class="t-body-s muted">{{ q.data ? settingsLine(q.data) : q.isError ? errorText(q.error) : '…' }}</span>
    </div>
    <Button variant="outline" icon="edit" :disabled="!q.data" @click="open = true">{{ tr('Change settings', 'সেটিং বদলান') }}</Button>
    <ReviewSettingsDialog v-if="open && q.data" :initial="q.data" @close="open = false" @saved="saved" />
  </section>
</template>
