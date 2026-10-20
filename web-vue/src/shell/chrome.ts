// পাতা থেকে খোলস (shell)-কে জানানো: শিরোনাম, আর মোবাইলে বিস্তারিত পাতা খোলা কি না
import { onBeforeUnmount, onMounted, reactive, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue';

export const chrome = reactive({ title: '', detailOpen: 0, subOpen: 0 });

export function useChrome() {
  return chrome;
}

/**
 * পাতার শিরোনাম (উপরের বার আর ব্রাউজারের ট্যাবে)।
 * React-এর useEffect-এর মতো বসার পরে (onMounted: ভেতরের আগে, বাইরের পরে — তাই বাইরের পাতার শিরোনামই থাকে), আর বদলালে আবার।
 */
export function usePageTitle(title: MaybeRefOrGetter<string>) {
  const apply = () => {
    const t = toValue(title);
    chrome.title = t;
    document.title = t ? `${t} · Lifolo` : 'Lifolo';
  };
  onMounted(apply);
  watch(() => toValue(title), apply);
}

/** মোবাইলে বিস্তারিত পাতা: উপরের বার আর নিচের ট্যাব লুকায় (অ্যাপের মতো পুরো পাতা) */
export function useDetailMode(active: MaybeRefOrGetter<boolean>) {
  watch(
    () => toValue(active),
    (on, _old, onCleanup) => {
      if (!on) return;
      chrome.detailOpen++;
      onCleanup(() => {
        chrome.detailOpen--;
      });
    },
    { immediate: true },
  );
}

/** মিডিয়া কোয়েরি মেলে কি না (ref) */
export function useMedia(query: string): Ref<boolean> {
  const m = ref(typeof window !== 'undefined' && window.matchMedia(query).matches);
  let mq: MediaQueryList | null = null;
  const f = () => {
    if (mq) m.value = mq.matches;
  };
  onMounted(() => {
    mq = window.matchMedia(query);
    f();
    mq.addEventListener('change', f);
  });
  onBeforeUnmount(() => mq?.removeEventListener('change', f));
  return m;
}
/** ফর্ম/সাব-পাতা: বড় পর্দায় উপরের বারের শিরোনাম লুকায় (পাতার নিজের শিরোনাম থাকে) */
export function useSubPage() {
  chrome.subOpen++;
  onBeforeUnmount(() => {
    chrome.subOpen--;
  });
}
export const useTwoPane = () => useMedia('(min-width: 960px)');
export const useMobile = () => useMedia('(max-width: 719px)');
