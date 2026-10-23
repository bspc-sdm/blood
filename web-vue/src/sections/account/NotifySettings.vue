<script setup lang="ts">
// সেটিংস › নোটিফিকেশন (অ্যাপের NotifySettings.kt): "সব নোটিফিকেশন" আর প্রতিটা ধরন আলাদা (notify_prefs_get/set)।
// বদল সাথে সাথে দেখায়, সেভ না হলে আগের অবস্থায় ফেরে আর টোস্ট। জরুরি রক্ত/অ্যাম্বুলেন্স বন্ধের আগে পাতার ভেতরে "নিশ্চিত?"।
// "আশেপাশে রক্ত লাগলে"-র নিচে আগের পছন্দগুলো (#nearby স্লট, SettingsPage দেয়); তার মান বদলালে `nearby` (প্রোফাইলের notify)।
import { onMounted, ref } from 'vue';
import { ConfirmDialog, ErrorBox, Loading, Switch, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import {
  BUSINESS_CAT, NOTIFY_CATS, URGENT_CATS, allLabel, allSub, bellNote, catLabel, catSub, loadNotifyPrefs, offConfirm, offText, offTitle,
  revertChange, saveFailed, saveNotifyPrefs, withChange, type NotifyCat, type NotifyChange, type NotifyPrefs,
} from '@/lib/notifyPrefs';
import SLabel from './SLabel.vue';
import { featureOn } from '@/lib/features';

const props = defineProps<{ showBusiness: boolean }>();
const emit = defineEmits<{ nearby: [on: boolean] }>();

const prefs = ref<NotifyPrefs | null>(null);
const loadError = ref<string | null>(null);
/** বন্ধ করার আগে নিশ্চিত চাওয়া হচ্ছে যেটার জন্য */
const confirmOff = ref<NotifyCat | null>(null);
/** "নিশ্চিত?"-তে বাতিল করলে সুইচটা আবার আঁকা (চেকবক্স নিজে থেকেই বন্ধ দেখাচ্ছিল) */
const rev = ref(0);
/** শেষ বদলের ক্রম — পরে আরেকটা বদল চললে তার উত্তরই শেষ কথা */
let seq = 0;

// অ্যাপে কল: ফিচার সুইচ calls_audio চালু থাকলে তবেই
const cats = () => NOTIFY_CATS.filter((c) => (c !== BUSINESS_CAT || props.showBusiness) && (c !== 'calls' || featureOn('calls_audio')));

function set(p: NotifyPrefs) {
  const was = prefs.value?.cats.blood_nearby;
  prefs.value = p;
  if (was !== p.cats.blood_nearby) emit('nearby', p.cats.blood_nearby);
}

async function load() {
  loadError.value = null;
  try {
    set(await loadNotifyPrefs());
  } catch (e) {
    loadError.value = errorText(e);
  }
}
onMounted(load);

async function apply(change: NotifyChange) {
  const before = prefs.value;
  if (!before) return;
  const mine = ++seq;
  set(withChange(before, change));
  try {
    const r = await saveNotifyPrefs(change);
    if (mine === seq) set(r);
  } catch {
    if (prefs.value) set(revertChange(prefs.value, before, change));
    toast(saveFailed());
  }
}

function toggle(target: 'all' | NotifyCat, on: boolean) {
  if (target === 'all') return void apply({ all: on });
  if (!on && URGENT_CATS.includes(target)) {
    confirmOff.value = target;
    return;
  }
  void apply({ cats: { [target]: on } });
}
function confirmTurnOff() {
  const c = confirmOff.value;
  confirmOff.value = null;
  if (c) void apply({ cats: { [c]: false } });
}
function cancelTurnOff() {
  confirmOff.value = null;
  rev.value++;
}
</script>

<template>
  <section class="acct-scard" data-testid="notify-settings">
    <SLabel icon="notifications">{{ tr('Notifications', 'নোটিফিকেশন') }}</SLabel>
    <ErrorBox v-if="loadError && !prefs" :error="loadError" :on-retry="load" />
    <div v-else-if="!prefs" class="acct-sbody" :style="{ alignItems: 'center' }"><Loading inline /></div>
    <template v-else>
      <div :key="`all-${rev}`" class="acct-srow" data-cat="all">
        <div class="grow">
          <Switch :model-value="prefs.all" :label="allLabel()" :sub="allSub(prefs.all)" @update:model-value="(v) => toggle('all', v)" />
        </div>
      </div>
      <hr class="divider" />
      <template v-for="c in cats()" :key="`${c}-${rev}`">
        <div class="acct-srow" :data-cat="c">
          <div class="grow">
            <Switch :model-value="prefs.cats[c]" :disabled="!prefs.all" :label="catLabel(c)" :sub="catSub(c)" @update:model-value="(v) => toggle(c, v)" />
          </div>
        </div>
        <div v-if="c === 'blood_nearby' && prefs.all && prefs.cats.blood_nearby" class="acct-sbody" data-testid="nearby-extras">
          <slot name="nearby" />
        </div>
      </template>
      <div class="acct-srow" data-cat="account">
        <div class="grow">
          <Switch :model-value="true" disabled :label="catLabel('account')" :sub="catSub('account')" />
        </div>
      </div>
    </template>
    <p class="acct-sbody t-body-s muted" data-testid="bell-note">{{ bellNote() }}</p>
    <ConfirmDialog v-if="confirmOff" :title="offTitle()" :text="offText(confirmOff)" :confirm="offConfirm()" danger @confirm="confirmTurnOff" @close="cancelTurnOff" />
  </section>
</template>
