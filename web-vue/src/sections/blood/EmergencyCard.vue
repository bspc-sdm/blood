<script setup lang="ts">
import { computed, ref } from 'vue';
import { Action, ErrorText, Icon, PhoneLine } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { districtLabel } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { isDonor } from '@/lib/profile';
import { UNKNOWN_GROUP } from '@/lib/rules';
import Countdown from './parts/Countdown.vue';
import { THANKS_DONATED, type Appeal } from './emergency';

const props = defineProps<{ e: Appeal }>();
const emit = defineEmits<{ donated: [m: string]; changed: [] }>();
const s = useSession();
const busy = ref(false);
const err = ref<string | null>(null);
async function run(f: () => Promise<unknown>, after?: () => void) {
  busy.value = true;
  err.value = null;
  try {
    await f();
    after?.();
    emit('changed');
  } catch (x) {
    err.value = errorText(x);
  } finally {
    busy.value = false;
  }
}
const my = computed(() => props.e.groups.find((g) => g.group === props.e.myGroup));
// লগইন ছাড়া, বা রক্তদাতার তথ্য দেওয়া নেই: "আসছি" চাপলে আগে সেগুলো চাওয়া হয়
const needDonor = computed(() => !props.e.myStatus && (s.guest || !isDonor(s.profile)));
const message = computed(() => {
  const e = props.e;
  return needDonor.value
    ? tr('Can you donate? Tap "I\'m coming" to add your donor details first.', 'রক্ত দিতে পারবেন? "আসছি" চাপুন, আগে রক্তদাতার তথ্য দিতে হবে।')
    : e.myStatus === 'coming'
      ? tr("You're coming — thank you! Please bring your ID.", 'আপনি আসছেন — ধন্যবাদ! সাথে পরিচয়পত্র আনুন।')
      : e.myStatus === 'donated'
        ? tr('You donated — thank you!', 'আপনি রক্ত দিয়েছেন — ধন্যবাদ!')
        : !e.myGroup || e.myGroup === UNKNOWN_GROUP
          ? tr('Add your blood group in your profile to respond. Please tell people you know.', 'সাড়া দিতে প্রোফাইলে রক্তের গ্রুপ দিন। পরিচিতদের জানাতে পারেন।')
          : !my.value
            ? tr("Your blood group isn't needed in this appeal. Please tell people you know.", 'এই আহ্বানে আপনার গ্রুপ দরকার নেই। পরিচিতদের জানাতে পারেন।')
            : my.value.full
              ? tr('Enough donors are already coming for your group — thank you!', 'আপনার গ্রুপের যথেষ্ট ডোনার আসছেন — ধন্যবাদ!')
              : !e.eligible
                ? tr("You can't donate right now (last donation, age or weight).", 'এখন আপনি রক্ত দিতে পারবেন না (শেষ রক্তদান, বয়স বা ওজন)।')
                : tr(`Your group (${e.myGroup}) is needed. Tap "I'm coming" only if you can go now.`, `আপনার গ্রুপ (${e.myGroup}) দরকার। এখনই যেতে পারলে তবেই "আসছি" চাপুন।`);
});
const donated = () => run(() => call('emergency_donated', { id: props.e.id, donated: true }), () => emit('donated', THANKS_DONATED()));
const cancel = () => run(() => call('emergency_respond', { id: props.e.id, op: 'cancel' }));
const coming = () => run(() => call('emergency_respond', { id: props.e.id, op: 'coming' }));
const askDonor = () => s.requireLogin(tr('Add your donor details to respond', 'সাড়া দিতে রক্তদাতার তথ্য দিন'), () => emit('changed'), { donor: true });
</script>

<template>
  <section class="card danger col gap-8 blood-emg">
    <div class="row gap-8 c-error">
      <Icon name="warning" :size="20" />
      <span class="t-label-l bold">{{ tr('Emergency appeal · Lifolo team', 'জরুরি আহ্বান · Lifolo টিম') }}</span>
    </div>
    <div class="col gap-2">
      <h3 class="t-title-m bold">{{ e.hospitalName }}</h3>
      <span class="t-body-s">{{ districtLabel(e.district) }}</span>
    </div>
    <p v-if="e.note.trim()" class="t-body-m pre">{{ e.note }}</p>
    <div class="row gap-8 blood-emg-groups">
      <div v-for="g in e.groups" :key="g.group" :class="['blood-emg-g', g.full ? 'full' : g.group === e.myGroup && 'mine']">
        <span class="t-title-s bold">{{ g.group }}</span>
        <span class="t-label-s">{{ g.full ? tr('Enough', 'যথেষ্ট') : tr(`${g.coming}/${g.need} coming`, `${num(g.coming)}/${num(g.need)} আসছেন`) }}</span>
      </div>
    </div>
    <Countdown :target="e.endsAt" />
    <p class="t-body-m semi">{{ message }}</p>
    <ErrorText :text="err" />
    <div class="row gap-8 wrap">
      <template v-if="e.myStatus === 'coming'">
        <Action icon="check_circle" primary :busy="busy" @click="donated">{{ tr('I donated', 'রক্ত দিয়েছি') }}</Action>
        <Action icon="close" :busy="busy" @click="cancel">{{ tr("Can't come", 'আসতে পারছি না') }}</Action>
      </template>
      <Action v-else-if="needDonor" icon="directions_run" primary :busy="busy" @click="askDonor">{{ tr("I'm coming", 'আসছি') }}</Action>
      <Action v-else-if="e.canCome" icon="directions_run" primary :busy="busy" @click="coming">{{ tr("I'm coming", 'আসছি') }}</Action>
    </div>
    <div v-if="e.contactPhone.trim()" class="col gap-4">
      <span class="t-label-m">{{ tr('Call hospital', 'হাসপাতালে ফোন') }}</span>
      <PhoneLine :phone="e.contactPhone" />
    </div>
  </section>
</template>
