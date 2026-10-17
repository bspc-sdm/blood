// থিম: ফোনের মতো (system) / লাইট / ডার্ক — অ্যাপের সেটিংসের মতো
import { useSyncExternalStore } from 'react';
import { store } from './store';

export type Theme = 'system' | 'light' | 'dark';
const KEY = 'lf.theme';
let theme: Theme = ((): Theme => {
  const t = store.get(KEY);
  return t === 'light' || t === 'dark' ? t : 'system';
})();
const subs = new Set<() => void>();

export function applyTheme() {
  const root = document.documentElement;
  if (theme === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme);
}
export function setTheme(t: Theme) {
  theme = t;
  store.set(KEY, t === 'system' ? null : t);
  applyTheme();
  subs.forEach((f) => f());
}
export function useTheme(): Theme {
  return useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => theme,
  );
}
