<script setup lang="ts">
// অ্যাপ সেটিংস (অ্যাপের Settings.kt): ভাষা, থিম, নোটিফিকেশন, দূরত্ব, যাতায়াত খরচ, তালিকার গ্রুপ, এলাকা, অ্যাপ সম্পর্কে।
// প্রতিটা বদল সাথে সাথে সেভ হয়। অতিথি: শুধু ভাষা, থিম, এলাকা, ব্যবসা যোগ আর অ্যাপ সম্পর্কে।
// নোটিফিকেশন: "সব নোটিফিকেশন" আর প্রতিটা ধরন আলাদা (notify_prefs_*); "আশেপাশে রক্ত লাগলে" = প্রোফাইলের notify,
// তার নিচে আগের মতো দিনে সর্বোচ্চ আর গ্রুপ। জরুরি ধরন (বা সব) বন্ধ করার আগে পাতার ভেতরেই "নিশ্চিত?"।
import { computed, ref, watch } from 'vue';
import { ChoiceChips, ConfirmDialog, ErrorText, Icon, ListRow, Loading, Segmented, Switch } from '@/ui/kit';
import PlacePicker from '@/ui/PlacePicker.vue';
import SubPageBar from '@/shell/SubPageBar.vue';
import { usePageTitle } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { num, setLang, tr, useLang, type Lang } from '@/lib/i18n';
import { setTheme, useTheme, type Theme } from '@/lib/theme';
import { districtLabel } from '@/lib/geo';
import { BLOOD_GROUPS, FARE_AFTER_CHOICES, PUSH_PER_DAY_CHOICES, RADIUS_CHOICES } from '@/lib/rules';
import { compatibleGroups, effectiveNotifyGroups, effectiveViewGroups, saveSettings, type Profile } from '@/lib/profile';
import { useSession, type Place } from '@/lib/session';
import {
  NOTIFY_CATS, URGENT_CATS, BUSINESS_CAT, allLabel, allSub, catLabel, catSub, loadNotifyPrefs, offConfirm, offText, offTitle, saveNotifyPrefs,
  type NotifyCat, type NotifyPrefs,
} from '@/lib/notifyPrefs';
import { useNav } from '@/lib/nav';
import { PRIVACY_URL, SUPPORT_EMAIL, TERMS_URL, hasBusiness, useAccountState } from './util';
import { useBack } from './nav';
import LinkRow from './LinkRow.vue';
import SLabel from './SLabel.vue';

/** সব মানানসই গ্রুপ বাছা থাকলে null — রক্তের গ্রুপ বদলালে নিজে থেকে মিলে যায় */
function normalize(set: string[], def: string[]): string[] | null {
  const same = set.length === def.length && def.every((g) => set.includes(g));
  return same ? null : BLOOD_GROUPS.filter((g) => set.includes(g));
}

const lang = useLang();
const theme = useTheme();
const s = useSession();
const nav = useNav();
const title = computed(() => tr('App settings', 'অ্যাপ সেটিংস'));
usePageTitle(title);
const back = useBack(() => (s.guest ? '/' : '/profile'));
const error = ref<string | null>(null);
const areaOpen = ref(false);
const areaBusy = ref(false);
const areaError = ref<string | null>(null);

const compatible = computed(() => (s.profile ? compatibleGroups(s.profile) : []));

function save(updated: Profile) {
  if (!s.uid) return;
  s.setProfile(updated);
  saveSettings(s.uid, updated).then(
    () => (error.value = null),
    () => (error.value = tr("Couldn't save. Check your internet.", 'সেভ হয়নি, ইন্টারনেট দেখুন।')),
  );
}
const upd = (patch: Partial<Profile>) => s.profile && save({ ...s.profile, ...patch });

// ---------- নোটিফিকেশনের ধরন ----------
const prefs = ref<NotifyPrefs | null>(null);
const prefsError = ref<string | null>(null);
/** বন্ধ করার আগে নিশ্চিত চাওয়া হচ্ছে যেটার জন্য */
const confirmOff = ref<'all' | NotifyCat | null>(null);
/** "নিশ্চিত?"-তে বাতিল করলে সুইচটা আবার আঁকা (চেকবক্স নিজে থেকেই বন্ধ দেখাচ্ছিল) */
const rev = ref(0);
/** "আমার ব্যবসা" শুধু ব্যবসা বা পেশাদার ব্যাজ (আবেদন সহ) থাকলে */
const acct = useAccountState();
const showBusiness = computed(() => hasBusiness(acct.value.work) || !!acct.value.work.professional || !!acct.value.me?.badge);
const cats = computed(() => NOTIFY_CATS.filter((c) => c !== BUSINESS_CAT || showBusiness.value));

async function loadPrefs() {
  if (s.guest || !s.uid) return;
  try {
    prefs.value = await loadNotifyPrefs();
    prefsError.value = null;
    syncProfile();
  } catch (e) {
    prefsError.value = errorText(e);
  }
}
watch(() => s.uid, loadPrefs, { immediate: true });

/** blood_nearby আর প্রোফাইলের notify একই — নিচের দিনে সর্বোচ্চ/গ্রুপের সেভ যেন পুরোনো মান না পাঠায় */
function syncProfile() {
  const p = s.profile;
  const v = prefs.value?.cats.blood_nearby;
  if (p && v != null && p.notify !== v) s.setProfile({ ...p, notify: v });
}

async function applyPrefs(change: { all?: boolean; cats?: Partial<Record<NotifyCat, boolean>> }) {
  const before = prefs.value;
  if (!before) return;
  prefs.value = { all: change.all ?? before.all, cats: { ...before.cats, ...change.cats } };
  syncProfile();
  try {
    prefs.value = await saveNotifyPrefs(change);
    prefsError.value = null;
  } catch {
    prefs.value = before;
    prefsError.value = tr("Couldn't save. Check your internet.", 'সেভ হয়নি, ইন্টারনেট দেখুন।');
  }
  syncProfile();
}

function toggle(target: 'all' | NotifyCat, on: boolean) {
  if (!on && (target === 'all' || URGENT_CATS.includes(target))) {
    confirmOff.value = target;
    return;
  }
  void applyPrefs(target === 'all' ? { all: on } : { cats: { [target]: on } });
}
function confirmTurnOff() {
  const t = confirmOff.value;
  confirmOff.value = null;
  if (t) void applyPrefs(t === 'all' ? { all: false } : { cats: { [t]: false } });
}
function cancelTurnOff() {
  confirmOff.value = null;
  rev.value++;
}

async function picked(pl: Place) {
  areaBusy.value = true;
  areaError.value = null;
  try {
    await s.setPlace(pl);
    areaOpen.value = false;
  } catch (e) {
    areaError.value = errorText(e);
  } finally {
    areaBusy.value = false;
  }
}
</script>

<template>
  <Loading v-if="!s.ready || (!s.guest && !s.profile)" />
  <template v-else>
    <SubPageBar :title="title" :on-back="back" />
    <div class="form-page">
      <div class="form-inner acct-settings">
        <ErrorText :text="error" />
        <div class="acct-scols">
          <div class="col gap-12">
            <section class="acct-scard">
              <SLabel icon="language">{{ tr('Language', 'ভাষা') }}</SLabel>
              <div class="acct-seg">
                <Segmented :model-value="lang" :label="tr('Language', 'ভাষা')" :options="[{ value: 'en', label: 'English' }, { value: 'bn', label: 'বাংলা' }]" @update:model-value="(v) => setLang(v as Lang)" />
              </div>
              <hr class="divider" />
              <SLabel icon="dark_mode">{{ tr('Theme', 'থিম') }}</SLabel>
              <div class="acct-seg">
                <Segmented
                  :model-value="theme"
                  :label="tr('Theme', 'থিম')"
                  :options="[
                    { value: 'system', label: tr('System', 'ফোনের মতো') },
                    { value: 'light', label: tr('Light', 'লাইট') },
                    { value: 'dark', label: tr('Dark', 'ডার্ক') },
                  ]"
                  @update:model-value="(v) => setTheme(v as Theme)"
                />
              </div>
            </section>
            <template v-if="!s.guest && s.profile">
              <section class="acct-scard" data-testid="notify-settings">
                <SLabel icon="notifications">{{ tr('Notifications', 'নোটিফিকেশন') }}</SLabel>
                <div v-if="prefsError" class="acct-sbody"><ErrorText :text="prefsError" /></div>
                <div v-if="!prefs && !prefsError" class="acct-sbody"><Loading inline /></div>
                <template v-if="prefs">
                  <div :key="`all-${rev}`" class="acct-srow">
                    <div class="grow">
                      <Switch :model-value="prefs.all" :label="allLabel()" :sub="allSub()" @update:model-value="(v) => toggle('all', v)" />
                    </div>
                  </div>
                  <hr class="divider" />
                  <template v-for="c in cats" :key="`${c}-${rev}`">
                    <div class="acct-srow" :data-cat="c">
                      <div class="grow">
                        <Switch :model-value="prefs.cats[c]" :disabled="!prefs.all" :label="catLabel(c)" :sub="catSub(c)" @update:model-value="(v) => toggle(c, v)" />
                      </div>
                    </div>
                    <div v-if="c === 'blood_nearby' && prefs.all && prefs.cats.blood_nearby" class="acct-sbody">
                      <span class="t-label-l">{{ tr('Maximum per day', 'দিনে সর্বোচ্চ') }}</span>
                      <ChoiceChips
                        :options="PUSH_PER_DAY_CHOICES.map((n) => ({ value: n, label: num(n) }))"
                        :model-value="s.profile.maxPushPerDay"
                        @update:model-value="(n: number) => upd({ maxPushPerDay: n })"
                      />
                      <p v-if="compatible.length === 0" class="t-body-s muted">{{ tr('Add your blood group in your profile to get donation requests.', 'রক্তদানের নোটিফিকেশন পেতে প্রোফাইলে রক্তের গ্রুপ দিন।') }}</p>
                      <template v-else>
                        <span class="t-label-l">{{ tr('For patient blood groups', 'রোগীর যে গ্রুপগুলোর জন্য') }}</span>
                        <ChoiceChips
                          multi
                          :options="compatible.map((g) => ({ value: g, label: g }))"
                          :model-value="effectiveNotifyGroups(s.profile)"
                          @update:model-value="(next: string[]) => next.length && upd({ notifyGroups: normalize(next, compatible) })"
                        />
                        <p class="t-body-s muted">{{ tr(`Only groups you (${s.profile.bloodGroup}) can donate to.`, `শুধু যাদের আপনি (${s.profile.bloodGroup}) রক্ত দিতে পারেন।`) }}</p>
                      </template>
                    </div>
                  </template>
                  <div class="acct-srow" data-cat="account">
                    <div class="grow">
                      <Switch :model-value="true" disabled :label="catLabel('account')" :sub="catSub('account')" />
                    </div>
                  </div>
                </template>
              </section>
              <section class="acct-scard">
                <SLabel icon="location_on">{{ tr('How far can you go to donate?', 'রক্ত দিতে কতদূর যেতে পারবেন?') }}</SLabel>
                <div class="acct-sbody">
                  <ChoiceChips
                    :options="RADIUS_CHOICES.map((k) => ({ value: k, label: num(k) + tr(' km', ' কিমি') }))"
                    :model-value="s.profile.maxKm"
                    @update:model-value="(k: number) => upd({ maxKm: k })"
                  />
                  <p class="t-body-s muted">{{ tr("You'll be notified only for requests within this distance.", 'এই দূরত্বের মধ্যে রিকোয়েস্ট হলেই নোটিফিকেশন পাবেন।') }}</p>
                </div>
                <hr class="divider" />
                <SLabel icon="directions_run">{{ tr('Travel cost', 'যাতায়াত খরচ') }}</SLabel>
                <div class="acct-sbody">
                  <ChoiceChips
                    :options="[
                      { value: 0, label: tr('Not needed', 'লাগবে না') },
                      ...FARE_AFTER_CHOICES.map((k) => ({ value: k, label: tr(`Beyond ${num(k)} km`, `${num(k)} কিমির বেশি হলে`) })),
                    ]"
                    :model-value="s.profile.fareAfterKm ?? 0"
                    @update:model-value="(k: number) => upd({ fareAfterKm: k || null })"
                  />
                  <p class="t-body-s muted">
                    {{ tr("If a request doesn't cover travel cost and it's farther than this, you won't be notified.", 'রিকোয়েস্টে যাতায়াত খরচ না থাকলে আর এর চেয়ে দূরে হলে নোটিফিকেশন আসবে না।') }}
                  </p>
                </div>
              </section>
            </template>
          </div>
          <div class="col gap-12">
            <section class="acct-scard">
              <template v-if="!s.guest && s.profile">
                <SLabel icon="tune">{{ tr('Show requests for', 'যে গ্রুপের রিকোয়েস্ট দেখাবে') }}</SLabel>
                <div class="acct-sbody">
                  <ChoiceChips
                    multi
                    :options="BLOOD_GROUPS.map((g) => ({ value: g, label: g }))"
                    :model-value="effectiveViewGroups(s.profile)"
                    @update:model-value="(next: string[]) => next.length && upd({ viewGroups: normalize(next, compatible) })"
                  />
                </div>
                <hr class="divider" />
              </template>
              <ListRow icon="location_on" :title="tr('Area', 'এলাকা')" :sub="districtLabel(s.place?.placeName) || tr('Choose area', 'এলাকা বেছে নিন')" :on-click="() => (areaOpen = true)" />
            </section>
            <section v-if="s.guest" class="acct-scard">
              <ListRow
                icon="storefront"
                :title="tr('Add your business', 'আপনার ব্যবসা যোগ করুন')"
                :sub="tr('Ambulance, pharmacy, blood bank, lab', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি')"
                :on-click="() => s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav('/work/add'))"
              />
            </section>
            <section class="acct-scard">
              <LinkRow icon="privacy_tip" :title="tr('Privacy policy', 'প্রাইভেসি পলিসি')" :href="PRIVACY_URL" />
              <LinkRow icon="list_alt" :title="tr('Terms of use', 'ব্যবহারের শর্ত')" :href="TERMS_URL" />
              <!-- অ্যাকাউন্ট মোছার পাবলিক পাতা (/delete-account): কী মোছে, কবে; লগইন থাকলে সেখান থেকেই মোছা -->
              <ListRow icon="delete" :title="tr('Delete account', 'অ্যাকাউন্ট মুছুন')" :on-click="() => nav('/delete-account')" />
              <ListRow icon="mail" :title="tr('Contact support', 'সাপোর্টে যোগাযোগ')" :sub="SUPPORT_EMAIL" :href="`mailto:${SUPPORT_EMAIL}`"><template #trailing><span /></template></ListRow>
              <div class="acct-srow static">
                <Icon name="info" :size="24" />
                <div class="col gap-2">
                  <span class="t-body-l">{{ tr('Version', 'ভার্সন') }}</span>
                  <span class="t-body-s muted">{{ tr('Web', 'ওয়েব') }}</span>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
    <ConfirmDialog
      v-if="confirmOff"
      :title="offTitle()"
      :text="offText(confirmOff)"
      :confirm="offConfirm()"
      danger
      @confirm="confirmTurnOff"
      @close="cancelTurnOff"
    />
    <PlacePicker v-if="areaOpen" :busy="areaBusy" :error="areaError" @close="areaOpen = false" @picked="picked" />
  </template>
</template>
