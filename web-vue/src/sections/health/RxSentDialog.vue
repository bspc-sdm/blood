<script setup lang="ts">
// পাঠানোর পর: বড় টিক, কী কোথায় গেল, এরপর কী — আর "প্রেসক্রিপশন খুলুন" (সেখানে অবস্থা আর "ফিরিয়ে নিন")
import { Button, Dialog, Icon, IconLine } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { sentModeIcon, sentModeText, sentNextText, type RxSent } from './rxactivity';

defineProps<{ s: RxSent }>();
const emit = defineEmits<{ open: []; close: [] }>();
</script>

<template>
  <Dialog size="small" labelled-by="rx-sent-title" @close="emit('close')">
    <div class="health-sent">
      <Icon name="check_circle" :size="64" class="health-sent-icon" />
      <h2 id="rx-sent-title" class="t-title-l">{{ tr('Prescription sent', 'প্রেসক্রিপশন পাঠানো হয়েছে') }}</h2>
    </div>
    <div class="col gap-6">
      <IconLine icon="medication" color="var(--on-surface)">{{ [s.doctorName || tr('Doctor', 'ডাক্তার'), formatDate(s.date)].join(' · ') }}</IconLine>
      <IconLine :icon="s.mode.startsWith('lab') ? 'local_hospital' : 'storefront'" color="var(--on-surface)">{{ s.place }}</IconLine>
      <IconLine :icon="sentModeIcon(s.mode)" color="var(--on-surface)">{{ sentModeText(s.mode) }}</IconLine>
    </div>
    <!-- এরপর কী -->
    <div class="card tonal row gap-8 health-sent-next">
      <Icon name="notifications" :size="20" />
      <span class="t-body-m">{{ sentNextText(s.mode) }}</span>
    </div>
    <template #actions>
      <Button variant="text" @click="emit('close')">{{ tr('OK', 'ঠিক আছে') }}</Button>
      <Button icon="medication" @click="emit('open')">{{ tr('Open prescription', 'প্রেসক্রিপশন খুলুন') }}</Button>
    </template>
  </Dialog>
</template>
