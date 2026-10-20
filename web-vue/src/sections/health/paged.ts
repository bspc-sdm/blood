// স্বাস্থ্য আলোচনা আর টিপসের তালিকা পাতায় পাতায় (before = শেষটার সময়) — অ্যাপের HealthDiscussionTab / HealthTipsTab
import { computed, reactive } from 'vue';
import { call } from '@/lib/api';
import { useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useInfiniteQ } from '@/lib/query';

export interface Question { id: string; text: string; authorName: string | null; mine: boolean; createdAt: number; answerCount: number; editedAt?: number; anonymous?: boolean }
export interface Answer { id: string; text: string; authorName: string; badge: string | null; mine: boolean; createdAt: number; editedAt?: number }
export interface Tip { id: string; title: string; body: string; authorName: string; badge: string | null; mine: boolean; createdAt: number; editedAt?: number }

const PAGE = 30;

export function usePaged<T extends { id: string; createdAt: number }>(action: 'questions' | 'tips') {
  const lang = useLang();
  const s = useSession();
  const q = useInfiniteQ<T[]>(() => ({
    queryKey: [action, 'pages', lang.value, s.uid],
    queryFn: ({ pageParam }: { pageParam: number }) => call<{ items?: T[] }>(action, pageParam ? { before: pageParam } : {}).then((r) => r.items ?? []),
    initialPageParam: 0,
    getNextPageParam: (last: T[]) => (last.length >= PAGE ? last[last.length - 1].createdAt : undefined),
  }));
  const items = computed(() => {
    const seen = new Set<string>();
    const out: T[] = [];
    for (const p of ((q.data as any)?.pages ?? []) as T[][]) for (const it of p) {
      if (!seen.has(it.id)) {
        seen.add(it.id);
        out.push(it);
      }
    }
    return out;
  });
  return reactive({ q, items }) as unknown as { q: any; items: T[] };
}
