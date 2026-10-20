<script setup lang="ts">
// প্রোফাইল এডিট (অ্যাপের ProfileEditScreen, FULL): নাম-মোবাইল আর রক্তদাতার তথ্য একসাথে
import { computed, ref } from 'vue';
import { Loading, toast } from '@/ui/kit';
import PlacePicker from '@/ui/PlacePicker.vue';
import FormPage from '@/shell/FormPage.vue';
import Redirect from '@/shell/Redirect.vue';
import ProfileForm from '@/account/ProfileForm.vue';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { hasLocation, isComplete, loadProfile, saveBasics, saveContact, type Profile } from '@/lib/profile';
import { useSession, type Place } from '@/lib/session';
import { TEST_MODE } from '@/lib/testmode';
import { useNav } from '@/lib/nav';

const s = useSession();
const nav = useNav();
const busy = ref(false);
const error = ref<string | null>(null);
const pickArea = ref(false);
const areaBusy = ref(false);
const areaError = ref<string | null>(null);
const full = computed(() => isComplete(s.profile));

async function save(next: Profile) {
  if (!s.uid) return;
  busy.value = true;
  error.value = null;
  try {
    // সার্ভার থেকে নতুন প্রোফাইল (রক্ত দেওয়ার পর সার্ভার তারিখ বসিয়ে থাকতে পারে)
    const fresh = (TEST_MODE ? null : await loadProfile(s.uid).catch(() => null)) ?? s.profile;
    const firstDate = fresh?.lastDonationMillis == null;
    if (full.value) await saveBasics(s.uid, next, firstDate);
    else await saveContact(s.uid, next.name, next.phone);
    const p = await s.reloadProfile();
    toast(tr('Saved', 'সেভ হয়েছে'));
    if (!hasLocation(p ?? next)) pickArea.value = true;
    else nav('/profile');
  } catch (e) {
    error.value = tr("Couldn't save", 'সেভ হয়নি') + ': ' + errorText(e);
  } finally {
    busy.value = false;
  }
}

function closeArea() {
  pickArea.value = false;
  nav('/profile');
}
async function picked(pl: Place) {
  areaBusy.value = true;
  areaError.value = null;
  try {
    await s.setPlace(pl);
    pickArea.value = false;
    nav('/profile');
  } catch (e) {
    areaError.value = errorText(e);
  } finally {
    areaBusy.value = false;
  }
}
</script>

<template>
  <Loading v-if="!s.ready" />
  <Redirect v-else-if="s.guest" to="/profile" />
  <FormPage v-else :title="full ? tr('Edit profile', 'প্রোফাইল এডিট') : tr('Your profile', 'আপনার প্রোফাইল')" back-to="/profile">
    <ProfileForm v-if="s.profileLoaded" :existing="s.profile" :mode="full ? 'full' : 'basic'" :busy="busy" :error="error" @save="(p) => save(p)" />
    <Loading v-else />
    <PlacePicker v-if="pickArea" :busy="areaBusy" :error="areaError" @close="closeArea" @picked="picked" />
  </FormPage>
</template>
