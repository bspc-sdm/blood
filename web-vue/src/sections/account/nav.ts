// পেছনে যাওয়া (সরাসরি লিংকে খুললে আগের পাতা না থাকলে নির্দিষ্ট পাতায়)
import { canGoBack, useNav } from '@/lib/nav';

export function useBack(fallback: string | (() => string)) {
  const nav = useNav();
  return () => {
    if (canGoBack()) nav(-1);
    else nav(typeof fallback === 'function' ? fallback() : fallback, { replace: true });
  };
}
