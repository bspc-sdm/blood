<script setup lang="ts">
// প্রোফাইল — লগইন করা
import { computed, onMounted, ref } from 'vue';
import { BloodGroupBadge, Button, Icon, IconLine, ListRow, toast } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { districtLabel } from '@/lib/geo';
import { ageYears, notEligibleReason } from '@/lib/rules';
import { isDonor, type Profile } from '@/lib/profile';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { store } from '@/lib/store';
import { useNav } from '@/lib/nav';
import DemoSwitchDialog from '@/demo/DemoSwitchDialog.vue'; // ডেমো
import { isDemoUid } from '@/demo/demo'; // ডেমো
import { usePhotoPicker } from './dialogs';
import DocsNeededCard from './DocsNeededCard.vue';
import DonationDateDialog from './DonationDateDialog.vue';
import CorrectionDialog from './CorrectionDialog.vue';
import PhoneVerifyDialog from './PhoneVerifyDialog.vue';
import PhotoMenuDialog from './PhotoMenuDialog.vue';
import AcctMenu from './AcctMenu';
import InfoLine from './InfoLine.vue';
import BecomeDonorCard from './BecomeDonorCard.vue';
import { SUPPORT_EMAIL, badgeLabel, hasBusiness, useAccountState } from './util';

const props = defineProps<{ profile: Profile }>();
const s = useSession();
const nav = useNav();
const acct = useAccountState();
const photo = usePhotoPicker();
const photoMenu = ref(false);
const demoSwitch = ref(false); // ডেমো
const dateDialog = ref(false);
const correctionDialog = ref(false);
function correctionSent() {
  correctionDialog.value = false;
  toast(tr('Request sent. An admin will check it.', 'অনুরোধ পাঠানো হয়েছে। অ্যাডমিন দেখে ঠিক করে দেবেন।')); // bn-ok
}
const correctionTitle = () => tr('Ask to correct the date', 'তথ্য সংশোধনের অনুরোধ'); // bn-ok
const correctionSub = () => tr('An admin checks and fixes your last donation date', 'অ্যাডমিন দেখে শেষ রক্তদানের তারিখ ঠিক করে দেবেন'); // bn-ok
const phoneDialog = ref(false);
const retrying = ref(false);
const bizKey = `lf.businessNo.${s.uid ?? ''}`;
const businessNo = ref(store.get(bizKey) === '1');
const assisting = useCall<{ items?: unknown[] }>('rx_assisting', {}, { staleTime: 5 * 60_000 });

// নিজের ব্যবসার হিসাব আগে আনা না গেলে আবার
onMounted(() => {
  if (!s.workLoaded) s.refreshWork().catch(() => {});
});

const me = computed(() => acct.value.me);
const workLoaded = computed(() => acct.value.workLoaded);
const donor = computed(() => isDonor(props.profile));
const notEligible = computed(() => (donor.value ? notEligibleReason(props.profile) : null));
const badge = computed(() => me.value?.badge ?? null);
const approved = computed(() => (badge.value?.status === 'approved' ? badge.value.type : null));
const owns = computed(() => hasBusiness(acct.value.work));
const age = computed(() => ageYears(props.profile.birthDateMillis));
const isAdmin = computed(() => !!me.value?.isAdmin || s.email.toLowerCase() === SUPPORT_EMAIL);
const needsPhoneCheck = computed(() => !!me.value?.phoneCheck && !me.value.phoneVerified);

const badgeSub = computed(() => {
  const b = badge.value;
  switch (b?.status) {
    case 'approved':
      return badgeLabel(b.type) + (b.changePending ? tr(' · correction under review', ' · সংশোধন যাচাই চলছে') : tr(' · tap to correct details', ' · তথ্য ঠিক করতে চাপুন'));
    case 'pending':
      return tr('Under review', 'যাচাই চলছে');
    case 'rejected':
      return tr('Not approved. Tap to apply again.', 'অনুমোদন হয়নি। আবার আবেদন করুন।');
    default:
      return tr('For doctors, nurses and health experts', 'ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞদের জন্য');
  }
});
const badgeLocked = computed(() => badge.value?.status === 'pending' || !!badge.value?.changePending);
const docs = computed(() => (Array.isArray(badge.value?.docs) ? (badge.value!.docs as { type: string; label: string; hint: string; required: boolean }[]) : []));
const docsMissing = computed(() => !!badge.value?.docsMissing && badge.value?.status === 'pending');
const docsRequested = computed(() => !!badge.value?.docsRequested);
const ageWeight = computed(() =>
  [age.value != null ? num(age.value) + tr(' yrs', ' বছর') : null, props.profile.weightKg != null ? num(props.profile.weightKg) + tr(' kg', ' কেজি') : null].filter(Boolean).join(' · '),
);
const photoSrc = computed(() => {
  const p = props.profile.photo;
  return p ? (p.startsWith('data:') ? p : `data:image/jpeg;base64,${p}`) : null;
});

async function logout() {
  await s.signOut();
  nav('/', { replace: true });
}
async function retryWork() {
  retrying.value = true;
  await s.refreshWork().catch(() => {});
  retrying.value = false;
}
function sayNo() {
  store.set(bizKey, '1');
  businessNo.value = true;
}
function dateSaved(d: number | null) {
  dateDialog.value = false;
  s.setProfile({ ...props.profile, lastDonationMillis: d });
  toast(tr('Saved', 'সেভ হয়েছে'));
}
function phoneVerified() {
  phoneDialog.value = false;
  s.reloadMe();
}
</script>

<template>
  <div class="acct-page">
    <h1 class="acct-mhead t-headline">{{ tr('Profile', 'প্রোফাইল') }}</h1>
    <div class="acct-profile">
      <div class="acct-col acct-left">
        <!-- ছবি, নাম, ব্যাজ -->
        <section class="card acct-summary acct-o1">
          <input :ref="(el) => (photo.input.value = el as HTMLInputElement | null)" type="file" accept="image/*" hidden @change="photo.onChange" />
          <div class="acct-photo-wrap">
            <button
              type="button"
              class="acct-photo"
              :disabled="photo.busy.value"
              :aria-label="tr('Profile photo', 'প্রোফাইলের ছবি')"
              @click="profile.photo ? (photoMenu = true) : photo.pick()"
            >
              <span v-if="photo.busy.value" class="spinner" />
              <img v-else-if="photoSrc" :src="photoSrc" :alt="tr('Profile photo', 'প্রোফাইলের ছবি')" />
              <Icon v-else name="person_filled" :size="56" />
            </button>
            <button type="button" class="acct-cam" :disabled="photo.busy.value" :aria-label="tr('Change photo', 'ছবি বদলান')" :title="tr('Change photo', 'ছবি বদলান')" @click="photo.pick()">
              <Icon name="photo_camera" :size="18" />
            </button>
          </div>
          <h2 class="t-title-l acct-name">{{ profile.name || '—' }}</h2>
          <span v-if="approved" class="tag good acct-badge"><Icon name="verified" :size="14" />{{ badgeLabel(approved) }}</span>
          <span v-if="s.email" class="t-body-s muted acct-email">{{ s.email }}</span>
          <p v-if="photo.error.value" class="err-text" role="alert">{{ photo.error.value }}</p>
        </section>

        <!-- রক্তের গ্রুপ ও যোগ্যতা -->
        <section v-if="donor" :class="`acct-elig acct-o2 ${notEligible ? '' : 'ok'}`">
          <BloodGroupBadge :group="profile.bloodGroup" :size="56" />
          <div class="col gap-2 grow">
            <span class="t-title-m bold">{{ notEligible ? tr('Not eligible right now', 'এখন রক্ত দিতে পারবেন না') : tr('You can donate now', 'এখন রক্ত দিতে পারবেন') }}</span>
            <span v-if="notEligible" class="t-body-s">{{ notEligible }}</span>
          </div>
          <Icon :name="notEligible ? 'schedule' : 'check_circle'" :size="28" />
        </section>
        <div v-else class="acct-o2">
          <BecomeDonorCard @click="s.requireLogin(tr('Add your donor details', 'রক্তদাতার তথ্য দিন'), () => {}, { donor: true })" />
        </div>

        <!-- তথ্য -->
        <section class="card acct-infos acct-o5">
          <InfoLine icon="history" :label="tr('Last donation', 'শেষ রক্তদান')" :value="profile.lastDonationMillis != null ? formatDate(profile.lastDonationMillis) : tr('Never', 'কখনো দেননি')" />
          <InfoLine icon="person_outline" :label="tr('Age · Weight', 'বয়স · ওজন')" :value="ageWeight" />
          <InfoLine icon="call" :label="tr('Phone', 'ফোন')" :value="num(profile.phone)">
            <template v-if="needsPhoneCheck" #trailing>
              <Button variant="text" small @click="phoneDialog = true">{{ tr('Verify', 'যাচাই করুন') }}</Button>
            </template>
          </InfoLine>
          <InfoLine icon="location_on" :label="tr('Area', 'এলাকা')" :value="districtLabel(profile.placeName)" />
        </section>
      </div>

      <div class="acct-col acct-right">
        <!-- নিজের ব্যবসার তথ্য আনা যায়নি: কারণ আর আবার চেষ্টা -->
        <section v-if="s.workError" class="card danger acct-o3 col gap-4">
          <span class="t-title-s bold">{{ tr("Couldn't load your business", 'আপনার ব্যবসার তথ্য আনা যায়নি') }}</span>
          <span class="t-body-s">{{ s.workError }}</span>
          <div>
            <Button variant="text" small :busy="retrying" @click="retryWork">{{ tr('Try again', 'আবার চেষ্টা করুন') }}</Button>
          </div>
        </section>
        <div v-if="workLoaded && !owns && !businessNo" class="acct-o4">
          <section class="acct-bizq">
            <div class="row gap-12">
              <Icon name="storefront" :size="24" />
              <h2 class="t-title-m bold">{{ tr('Do you have a business?', 'আপনার কি কোনো ব্যবসা আছে?') }}</h2>
            </div>
            <p class="t-body-s">
              {{ tr('Ambulance, pharmacy, blood bank or lab — add it so patients nearby can find you.', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক বা প্যাথলজি — যোগ করলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন।') }}
            </p>
            <div class="row gap-8">
              <Button @click="nav('/work/add')">{{ tr('Yes, add it', 'হ্যাঁ, যোগ করুন') }}</Button>
              <Button variant="text" @click="sayNo">{{ tr('No', 'না') }}</Button>
            </div>
          </section>
        </div>

        <!-- কাজ -->
        <div class="acct-o6 col gap-12">
          <AcctMenu>
            <ListRow icon="list_alt" :title="tr('My Requests', 'আমার রিকোয়েস্ট')" :on-click="() => nav('/my-requests')" />
            <ListRow icon="calendar_month" :title="tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')" :on-click="() => nav('/my-appointments')" />
            <ListRow icon="medication" :title="tr('My prescriptions', 'আমার প্রেসক্রিপশন')" :on-click="() => nav('/prescriptions')" />
            <ListRow
              icon="health_and_safety_filled"
              :title="tr('Caregivers & family', 'কেয়ারগিভার ও পরিবার')"
              :sub="tr('People who can help you in the app', 'যাঁরা অ্যাপে আপনার হয়ে কাজ করতে পারেন')"
              :on-click="() => nav('/care')"
            />
            <ListRow v-if="assisting.data?.items?.length" icon="group_round" :title="tr('Doctor\'s assistant', 'ডাক্তারের সহকারী')" :on-click="() => nav('/work/rx')" />
          </AcctMenu>
          <AcctMenu>
            <ListRow
              icon="history"
              :title="tr('Change last donation date', 'শেষ রক্তদানের তারিখ বদলান')"
              :sub="profile.lastDonationMillis != null ? formatDate(profile.lastDonationMillis) : tr('Never donated', 'কখনো দেননি')"
              :on-click="() => (dateDialog = true)"
            />
            <ListRow
              icon="edit"
              :title="correctionTitle()"
              :sub="correctionSub()"
              :on-click="() => (correctionDialog = true)"
            />
            <ListRow icon="edit" :title="tr('Edit profile', 'প্রোফাইল এডিট')" :on-click="() => nav('/profile/edit')" />
            <ListRow
              v-if="workLoaded && !owns && businessNo"
              icon="storefront"
              :title="tr('Add a business', 'ব্যবসা যোগ করুন')"
              :sub="tr('Ambulance, pharmacy, blood bank, lab', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি')"
              :on-click="() => nav('/work/add')"
            />
            <div>
              <ListRow icon="workspace_premium" :title="tr('Professional badge', 'পেশাদার ব্যাজ')" :sub="badgeSub" :on-click="badgeLocked ? undefined : () => nav('/health/badge')" />
              <div v-if="badge && badge.statusReason && ['rejected', 'suspended'].includes(badge.status)" class="acct-under">
                <IconLine icon="info">{{ tr('Reason: ', 'কারণ: ') + badge.statusReason }}</IconLine>
              </div>
              <div v-if="badge && s.uid && (docsMissing || docsRequested)" class="acct-under">
                <DocsNeededCard :uid="s.uid" :docs="docs" :requested="docsRequested" :note="String(badge.docsRequestNote ?? '')" @done="s.reloadMe()" />
              </div>
            </div>
          </AcctMenu>
          <AcctMenu>
            <ListRow icon="settings_outline" :title="tr('App settings', 'অ্যাপ সেটিংস')" :sub="tr('Language, theme, notifications, area', 'ভাষা, থিম, নোটিফিকেশন, এলাকা')" :on-click="() => nav('/settings')" />
            <ListRow icon="help" :title="tr('Help & appeals', 'সাহায্য ও আপিল')" :on-click="() => nav('/help')" />
            <ListRow v-if="isAdmin" icon="admin_panel_settings" :title="tr('Admin', 'অ্যাডমিন')" href="/admin" />
          </AcctMenu>
          <AcctMenu>
            <!-- ডেমো -->
            <ListRow v-if="isDemoUid(s.uid)" icon="sync" :title="tr('Switch role', 'রোল বদলান')" :sub="tr('Demo', 'ডেমো')" :on-click="() => (demoSwitch = true)" />
            <ListRow icon="logout" :title="tr('Log out', 'লগআউট')" :on-click="logout"><template #trailing><span /></template></ListRow>
            <!-- সাময়িক বন্ধ আর মোছা — দুটোই পুরো পাতা (/deactivate-account, /delete-account) -->
            <ListRow icon="schedule" :title="tr('Pause account', 'অ্যাকাউন্ট সাময়িক বন্ধ')" :on-click="() => nav('/deactivate-account')" /><!-- bn-ok -->
            <ListRow icon="delete" :title="tr('Delete account', 'অ্যাকাউন্ট মুছুন')" danger :on-click="() => nav('/delete-account')" />
          </AcctMenu>
        </div>
      </div>
    </div>

    <!-- ডেমো -->
    <DemoSwitchDialog v-if="demoSwitch" @close="demoSwitch = false" @done="() => { demoSwitch = false; nav('/', { replace: true }); }" />
    <PhotoMenuDialog v-if="photoMenu" @close="photoMenu = false" @change="photo.pick()" @remove="photo.remove()" />
    <DonationDateDialog v-if="dateDialog" :profile="profile" @close="dateDialog = false" @saved="dateSaved" />
    <CorrectionDialog v-if="correctionDialog" :profile="profile" @close="correctionDialog = false" @sent="correctionSent" />
    <PhoneVerifyDialog v-if="phoneDialog" @close="phoneDialog = false" @verified="phoneVerified" />
  </div>
</template>
