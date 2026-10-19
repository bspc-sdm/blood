<script setup lang="ts">
// প্রেসক্রিপশন, টিম আর সহকারী (আমার কাজ › প্রেসক্রিপশন)
import { RouterLink } from 'vue-router';
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
</script>

<template>
  <RouterLink class="card click appt-panel-card" to="/work/rx">
    <span class="row wrap gap-16">
      <span class="appt-panel-ico"><Icon name="medication" /></span>
      <span class="col gap-2 grow">
        <span class="t-title-m">{{ tr('Prescriptions', 'প্রেসক্রিপশন') }}</span>
        <span class="t-body-s muted">{{ tr('Prescriptions, patients, team and assistants', 'প্রেসক্রিপশন, রোগী, টিম ও সহকারী') }}</span>
      </span>
      <span class="btn text has-icon"><Icon name="chevron_right" />{{ tr('Open', 'খুলুন') }}</span>
    </span>
  </RouterLink>
</template>
