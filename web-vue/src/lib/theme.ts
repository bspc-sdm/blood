// থিম: ফোনের মতো (system) / লাইট / ডার্ক — অ্যাপের সেটিংসের মতো
import { ref, type Ref } from 'vue';
import { store } from './store';

export type Theme = 'system' | 'light' | 'dark';
const KEY = 'lf.theme';
const theme: Ref<Theme> = ref(((): Theme => {
  const t = store.get(KEY);
  return t === 'light' || t === 'dark' ? t : 'system';
})());

export function applyTheme() {
  const root = document.documentElement;
  if (theme.value === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme.value);
}
export function setTheme(t: Theme) {
  theme.value = t;
  store.set(KEY, t === 'system' ? null : t);
  applyTheme();
}
export function useTheme(): Readonly<Ref<Theme>> {
  return theme;
}
