<script setup lang="ts">
// শেষ রক্তদানের তারিখ বদলানো (অ্যাপের Corrections.kt): পেছালে বা ১২০ দিনের কম ফাঁকে লাল সতর্কতা আর "জেনেই সিদ্ধান্ত" টিক
import { computed, ref } from 'vue';
import { Button, Check, Dialog, ErrorText, Icon, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { dateInputValue, formatDate, fromDateInput, todayUtcMidnight } from '@/lib/format';
import { DAY_MS } from '@/lib/rules';
import type { Profile } from '@/lib/profile';
import { donationRisk } from './dialogs';

const props = defineProps<{ profile: Profile }>();
const emit = defineEmits<{ close: []; saved: [v: number | null] }>();
const before = props.profile.lastDonationMillis ?? null;
const never = ref(before == null);
const date = ref<number | null>(before);
const agreed = ref(false);
const busy = ref(false);
const error = ref<string | null>(null);
const after = computed(() => (never.value ? null : date.value));
const risk = computed(() => donationRisk(before, after.value));
const changed = computed(() => after.value !== before);
const can = computed(() => !busy.value && changed.value && (never.value || date.value != null) && (risk.value == null || agreed.value));
const gap = computed(() => Math.round(((after.value ?? 0) - (before ?? 0)) / DAY_MS));

async function save() {
  if ((after.value ?? 0) > todayUtcMidnight()) {
    error.value = tr("Date can't be in the future", 'ভবিষ্যতের তারিখ দেওয়া যাবে না');
    return;
  }
  busy.value = true;
  error.value = null;
  try {
    await call('donation_date_set', { millis: after.value, confirmed: risk.value != null && agreed.value });
    emit('saved', after.value);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function setNever(v: boolean) {
  never.value = v;
  agreed.value = false;
}
function setDate(v: string) {
  date.value = fromDateInput(v);
  agreed.value = false;
}
</script>

<template>
  <Dialog size="small" :title="tr('Last donation date', 'শেষ রক্তদানের তারিখ')" @close="() => !busy && emit('close')">
    <p class="t-body-m muted">{{ tr('Now: ', 'এখন: ') + (before != null ? formatDate(before) : tr('Never donated', 'কখনো দেননি')) }}</p>
    <Check :model-value="never" @update:model-value="setNever">{{ tr('I have never donated', 'কখনো রক্ত দিইনি') }}</Check>
    <TextInput
      v-if="!never"
      type="date"
      :aria-label="tr('Pick the date', 'তারিখ বেছে নিন')"
      :max="dateInputValue(todayUtcMidnight())"
      :model-value="dateInputValue(date)"
      @update:model-value="setDate"
    />
    <div v-if="changed && risk" class="acct-risk" role="alert">
      <div class="row gap-6">
        <Icon name="warning" :size="18" :style="{ color: 'var(--error)' }" />
        <span class="t-title-s">{{ tr('Health risk', 'স্বাস্থ্যঝুঁকি') }}</span>
      </div>
      <p class="t-body-s">
        {{
          risk === 'backward'
            ? tr(
                "You're moving the date back. You'll show as able to donate earlier. Donating less than 120 days after your last donation is risky for you and the patient.",
                'আপনি তারিখ পেছাচ্ছেন — আগের চেয়ে আগেই "রক্ত দিতে পারবেন" দেখাবে। আগের রক্তদানের ১২০ দিনের আগে আবার রক্ত দেওয়া আপনার ও রোগীর জন্য ঝুঁকিপূর্ণ।',
              )
            : tr(
                `Only ${gap} days between the two donations. Less than 120 days between donations is a health risk.`,
                `দুই রক্তদানের মাঝে মাত্র ${num(gap)} দিন। ১২০ দিনের কম ফাঁকে রক্ত দেওয়া স্বাস্থ্যঝুঁকি।`,
              )
        }}
      </p>
      <Check v-model="agreed"><span class="semi">{{ tr('I understand and have decided', 'আমি জেনেই সিদ্ধান্ত নিয়েছি') }}</span></Check>
    </div>
    <ErrorText :text="error" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!can" @click="save">{{ tr('Confirm', 'নিশ্চিত করুন') }}</Button>
    </template>
  </Dialog>
</template>
