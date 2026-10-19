<script setup lang="ts">
// "রক্ত পেয়েছি": কত ব্যাগ পেয়েছেন, কে কে রক্ত দিয়েছেন
import { computed, ref } from 'vue';
import { BloodBag, Button, Check, Dialog, Icon } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { willing, type DonorResponse } from '../model';

const props = defineProps<{ bags: number; already: number; list: DonorResponse[] }>();
const emit = defineEmits<{ dismiss: []; close: [donated: string[], got: number] }>();
const w = computed(() => willing(props.list));
const donatedBefore = computed(() => w.value.filter((d) => d.status === 'donated').length);
const open = computed(() => w.value.filter((d) => d.status === 'accepted'));
const maxBags = Math.max(props.bags, 1);
const clampAlready = Math.min(Math.max(props.already, 1), maxBags);
const received = ref(clampAlready);
const donated = ref<string[]>([]);
const room = computed(() => Math.max(received.value - donatedBefore.value, 0));
const setReceived = (n: number) => {
  received.value = n;
  donated.value = donated.value.slice(0, Math.max(n - donatedBefore.value, 0));
};
const notNeeded = computed(() => received.value === 0);
const closes = computed(() => notNeeded.value || received.value >= props.bags);
const title = computed(() =>
  notNeeded.value ? tr('Blood no longer needed?', 'রক্ত আর লাগবে না?') : closes.value ? tr('Close this request?', 'রিকোয়েস্ট বন্ধ করবেন?') : tr('Blood received so far', 'এখন পর্যন্ত কত রক্ত পেলেন'),
);
function toggle(uid: string, v: boolean) {
  donated.value = v ? [...donated.value, uid] : donated.value.filter((x) => x !== uid);
}
</script>

<template>
  <Dialog :title="title" @close="emit('dismiss')">
    <div class="col gap-8">
      <span class="semi">{{ tr('How many bags did you get?', 'কত ব্যাগ পেয়েছেন?') }}</span>
      <div class="row gap-8 blood-bags">
        <button v-for="i in maxBags" :key="i - 1" type="button" class="blood-bagbtn" :aria-pressed="i <= received" @click="setReceived(i)">
          <BloodBag :n="i" :filled="i <= received" :label="tr(`${i} bag(s)`, `${num(i)} ব্যাগ`)" />
        </button>
      </div>
      <div class="chips">
        <button type="button" class="chip" :aria-pressed="notNeeded" @click="setReceived(notNeeded ? clampAlready : 0)">
          <Icon v-if="notNeeded" name="check_circle" />{{ tr('Not needed anymore', 'আর লাগবে না') }}
        </button>
      </div>
      <p class="t-body-s muted">
        {{ notNeeded
          ? tr('The request will close. Donors who agreed will be told they don\'t need to come.', 'রিকোয়েস্ট বন্ধ হবে। রাজি ডোনারদের জানানো হবে যে আসতে হবে না।')
          : closes
            ? tr('All bags received — the request will close and donors won\'t see it anymore.', 'সব ব্যাগ পেয়েছেন — রিকোয়েস্ট বন্ধ হবে, ডোনাররা আর দেখবেন না।')
            : tr(
              `${num(received)} of ${num(bags)} received. The request stays open for the remaining ${num(bags - received)}.`,
              `${num(bags)} ব্যাগের ${num(received)}টা পেয়েছেন। বাকি ${num(bags - received)}টার জন্য রিকোয়েস্ট চালু থাকবে।`,
            ) }}
      </p>
      <p v-if="closes" class="t-body-s muted">{{ tr('If it\'s a mistake, you can open it again within 1 hour.', 'ভুল হলে ১ ঘণ্টার মধ্যে আবার খুলতে পারবেন।') }}</p>
      <template v-if="!notNeeded && open.length">
        <span class="semi">{{ tr(`Who donated? (up to ${num(room)})`, `কে কে রক্ত দিয়েছেন? (${num(room)} জন পর্যন্ত)`) }}</span>
        <Check
          v-for="d in open"
          :key="d.donorUid"
          :model-value="donated.includes(d.donorUid)"
          :disabled="!donated.includes(d.donorUid) && donated.length >= room"
          @update:model-value="(v: boolean) => toggle(d.donorUid, v)"
        >
          {{ `${d.donorName} (${d.donorGroup})` + (d.confirmed ? tr(' · confirmed', ' · নিশ্চিত') : '') }}
        </Check>
      </template>
    </div>
    <template #actions>
      <Button variant="text" @click="emit('dismiss')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button @click="emit('close', notNeeded ? [] : donated, received)">{{ closes ? tr('Close', 'বন্ধ করুন') : tr('Save', 'সেভ করুন') }}</Button>
    </template>
  </Dialog>
</template>
