// ডায়ালগের হিসাব: শেষ রক্তদানের তারিখের ঝুঁকি (অ্যাপের Rules.donationRisk), প্রোফাইলের ছবি বাছাই (Profile.kt)
import { ref } from 'vue';
import { tr } from '@/lib/i18n';
import { DAY_MS, ELIGIBILITY_DAYS } from '@/lib/rules';
import { savePhoto } from '@/lib/profile';
import { useSession } from '@/lib/session';
import { toAvatar } from './util';

/** অ্যাপের Rules.donationRisk */
export function donationRisk(before: number | null, after: number | null): 'backward' | 'short_gap' | null {
  if (before == null) return null;
  if (after == null || after < before) return 'backward';
  if (after > before && after - before < ELIGIBILITY_DAYS * DAY_MS) return 'short_gap';
  return null;
}

/** প্রোফাইলের ছবি: বাছাই → ছোট করে সেভ; থাকলে "বদলান / মুছুন"। input-টা পাতায় বসে (ref="photo.input") */
export function usePhotoPicker() {
  const s = useSession();
  const input = ref<HTMLInputElement | null>(null);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function onFile(file: File | undefined) {
    if (!file || !s.uid || !s.profile) return;
    busy.value = true;
    error.value = null;
    try {
      const { photo, thumb } = await toAvatar(file);
      await savePhoto(s.uid, photo, thumb);
      s.setProfile({ ...s.profile, photo });
    } catch {
      error.value = tr("Couldn't upload the photo", 'ছবি আপলোড হয়নি');
    } finally {
      busy.value = false;
    }
  }
  async function remove() {
    if (!s.uid || !s.profile) return;
    error.value = null;
    try {
      await savePhoto(s.uid, null, null);
      s.setProfile({ ...s.profile, photo: null });
    } catch {
      error.value = tr("Couldn't remove the photo", 'ছবি মোছা যায়নি');
    }
  }
  function onChange(e: Event) {
    const t = e.target as HTMLInputElement;
    onFile(t.files?.[0]);
    t.value = '';
  }
  return { input, onChange, pick: () => input.value?.click(), remove, busy, error };
}
