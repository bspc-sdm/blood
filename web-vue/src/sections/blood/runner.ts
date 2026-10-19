// কাজ চালানো: চলার সময় busy, ভুল হলে বার্তা, শেষে নতুন করে আনা
import { ref } from 'vue';
import { errorText } from '@/lib/api';
import { useInvalidate } from './data';

export function useRunner() {
  const invalidate = useInvalidate();
  const busy = ref<string | null>(null);
  const error = ref<string | null>(null);
  async function run(key: string, f: () => Promise<unknown>) {
    busy.value = key;
    error.value = null;
    try {
      await f();
      await invalidate();
      return true;
    } catch (e) {
      error.value = errorText(e);
      return false;
    } finally {
      busy.value = null;
    }
  }
  return { busy, error, run };
}

/** অ্যাপের ভেতরে আগের পাতা থাকলে সেখানে, নইলে fallback-এ */
export { canGoBack } from '@/lib/nav';
