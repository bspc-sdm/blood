// ডেটা আনা (react-query): একই অনুরোধ একবার, পাতা বদলে ফিরে এলে আগেরটা দেখিয়ে নতুন আনে
import { QueryClient, useQuery, type UseQueryOptions } from '@tanstack/react-query';
import { call } from './api';
import { getLang } from './i18n';
import { auth } from './firebase';

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

/** একটা সার্ভার-কাজের ফল: useCall('pharmacies', { district }) — body বদলালে নতুন করে আনে */
export function useCall<T = any>(
  action: string,
  body: Record<string, unknown> = {},
  opts: Omit<UseQueryOptions<T, Error, T, unknown[]>, 'queryKey' | 'queryFn'> = {},
) {
  return useQuery<T, Error, T, unknown[]>({
    queryKey: [action, body, getLang(), auth.currentUser?.uid ?? null],
    queryFn: () => call<T>(action, body),
    ...opts,
  });
}
