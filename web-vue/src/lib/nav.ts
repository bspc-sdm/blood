// React Router-এর ছোট ছোট কাজের Vue Router রূপ: nav('/path', { replace, state }), nav(-1), পথ মেলানো (matchPath),
// ?চাবি=মান (useSearchParams), আর ইতিহাসের state (React-এর location.state)।
import { computed, type ComputedRef } from 'vue';
import { useRoute, useRouter, type Router } from 'vue-router';

export interface NavOpts { replace?: boolean; state?: Record<string, unknown> | null }
export type NavFn = (to: string | number, opts?: NavOpts) => void;

export function navWith(router: Router): NavFn {
  return (to, opts = {}) => {
    if (typeof to === 'number') {
      router.go(to);
      return;
    }
    const r = router.resolve(to);
    const target = { path: r.path, query: r.query, hash: r.hash, state: (opts.state ?? undefined) as any };
    (opts.replace ? router.replace(target) : router.push(target)).catch(() => {});
  };
}

/** React-এর useNavigate() */
export function useNav(): NavFn {
  return navWith(useRouter());
}

/** অ্যাপের ভেতরে আগের পাতা আছে কি না (সরাসরি লিংকে খুললে নেই) — React Router-এর history.state.idx > 0 */
export const canGoBack = () => (window.history.state as { back?: string | null } | null)?.back != null;

/** React-এর useLocation(): pathname, search ("?a=b" বা ""), state */
export function useLoc(): ComputedRef<{ pathname: string; search: string; state: any }> {
  const route = useRoute();
  return computed(() => {
    const i = route.fullPath.indexOf('?');
    const h = route.fullPath.indexOf('#');
    const search = i < 0 ? '' : route.fullPath.slice(i, h > i ? h : undefined);
    void route.fullPath;
    return { pathname: route.path, search, state: (window.history.state as any) ?? null };
  });
}

/** React-এর useSearchParams(): [URLSearchParams (computed), বদলানো] */
export function useSearchParams(): [ComputedRef<URLSearchParams>, (p: URLSearchParams | Record<string, string>, o?: { replace?: boolean }) => void] {
  const route = useRoute();
  const router = useRouter();
  const sp = computed(() => {
    const i = route.fullPath.indexOf('?');
    const h = route.fullPath.indexOf('#');
    return new URLSearchParams(i < 0 ? '' : route.fullPath.slice(i + 1, h > i ? h : undefined));
  });
  const set = (p: URLSearchParams | Record<string, string>, o: { replace?: boolean } = {}) => {
    const q = (p instanceof URLSearchParams ? p : new URLSearchParams(p)).toString();
    const to = route.path + (q ? `?${q}` : '');
    (o.replace ? router.replace(to) : router.push(to)).catch(() => {});
  };
  return [sp, set];
}

/** React Router-এর matchPath: "/a/:id", "/a/*"; end=false হলে শুরুটা মিললেই */
export function matchPath(pattern: string | { path: string; end?: boolean }, pathname: string): { params: Record<string, string> } | null {
  const p = typeof pattern === 'string' ? { path: pattern, end: true } : { end: true, ...pattern };
  const names: string[] = [];
  let star = false;
  let src = p.path.replace(/\/*\*?$/, (m) => {
    if (m.includes('*')) star = true;
    return '';
  });
  src = src
    .replace(/[.+?^${}()|[\]\\]/g, '\\$&')
    .replace(/\/:([\w-]+)/g, (_m, n: string) => {
      names.push(n);
      return '/([^\\/]+)';
    });
  let re: string;
  if (star) {
    names.push('*');
    re = src === '' || src === '/' ? '^(?:\\/(.*))?\\/*$' : `^${src}(?:\\/(.+)|\\/*)$`;
  } else if (p.end) re = `^${src}\\/*$`;
  else re = src === '' || src === '/' ? '^\\/?' : `^${src}(?:(?=\\/|$))`;
  const m = new RegExp(re, 'i').exec(pathname);
  if (!m) return null;
  const params: Record<string, string> = {};
  names.forEach((n, i) => {
    const v = m[i + 1];
    params[n] = v == null ? '' : safeDecode(v);
  });
  return { params };
}
const safeDecode = (v: string) => {
  try {
    return decodeURIComponent(v);
  } catch {
    return v;
  }
};
