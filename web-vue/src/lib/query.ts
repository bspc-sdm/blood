// ডেটা আনা (vue-query, React-এর react-query-র একই লাইব্রেরি): একই অনুরোধ একবার, পাতা বদলে ফিরে এলে আগেরটা দেখিয়ে নতুন আনে
import { QueryClient, useInfiniteQuery, useQuery, type UseQueryOptions } from '@tanstack/vue-query';
import { reactive, toValue, type MaybeRefOrGetter } from 'vue';
import { call } from './api';
import { getLang } from './i18n';
import { authUid } from './uid';
import { careUid, isCareAction, onCareChange, useCareMode } from './care';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 10 * 60_000,
      retry: (n, e: any) => n < 1 && !(e && e.status >= 400 && e.status < 500),
      refetchOnWindowFocus: false,
    },
  },
});

// কেয়ারগিভার মোড চালু/বন্ধ হলে কেয়ারের কাজগুলোর পুরোনো ফল বাদ (অন্যজনের তথ্য যেন না দেখায়)
onCareChange(() => {
  queryClient.removeQueries({ predicate: (q) => isCareAction(String(q.queryKey[0])) });
});

/** React-এর useQuery-র মতো ফল: q.data, q.isLoading … সরাসরি (ref নয়) — টেমপ্লেট আর স্ক্রিপ্টে একই রকম */
export function useQ<T = any>(options: MaybeRefOrGetter<UseQueryOptions<T, Error, T, T, readonly unknown[]>>) {
  return reactive(useQuery<T, Error, T, readonly unknown[]>(options as any));
}
export function useInfiniteQ<T = any>(options: MaybeRefOrGetter<any>) {
  return reactive(useInfiniteQuery<T, Error>(options as any));
}

type CallOpts<T> = Omit<UseQueryOptions<T, Error, T, T, readonly unknown[]>, 'queryKey' | 'queryFn'>;

/** একটা সার্ভার-কাজের ফল: useCall('pharmacies', () => ({ district })) — body বদলালে নতুন করে আনে */
export function useCall<T = any>(
  action: string,
  body: MaybeRefOrGetter<Record<string, unknown>> = {},
  opts: MaybeRefOrGetter<CallOpts<T>> = {},
) {
  const care = useCareMode();
  return useQ<T>(() => ({
    queryKey: [action, toValue(body), getLang(), authUid.value, isCareAction(action) ? care.value?.uid ?? careUid() : null],
    queryFn: () => call<T>(action, toValue(body)),
    ...toValue(opts),
  }));
}
