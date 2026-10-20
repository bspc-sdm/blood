import { createApp } from 'vue';
import { VueQueryPlugin } from '@tanstack/vue-query';
import '@fontsource/roboto/latin-400.css';
import '@fontsource/roboto/latin-500.css';
import '@fontsource/roboto/latin-700.css';
import '@fontsource/roboto/latin-900.css';
import '@fontsource/hind-siliguri/bengali-400.css';
import '@fontsource/hind-siliguri/bengali-500.css';
import '@fontsource/hind-siliguri/bengali-600.css';
import '@fontsource/hind-siliguri/bengali-700.css';
import '@fontsource/hind-siliguri/latin-400.css';
import './main.css';
import App from './App.vue';
import { router } from './router';
import { queryClient } from './lib/query';
import { initSession } from './lib/session';
import { applyTheme } from './lib/theme';
import { getLang } from './lib/i18n';
import { vFocus } from './ui/composables';
import { initSentry } from './lib/sentry';

applyTheme();
// নতুন ডিপ্লয়ের পর পুরোনো ট্যাবে কোনো অংশের ফাইল না পেলে একবার নিজে থেকে আবার লোড
window.addEventListener('vite:preloadError', (e) => {
  e.preventDefault();
  try {
    const k = 'lf.reloadedAt';
    const last = Number(sessionStorage.getItem(k) || 0);
    if (Date.now() - last < 10000) return;
    sessionStorage.setItem(k, String(Date.now()));
  } catch {
    /* তবুও লোড */
  }
  window.location.reload();
});
document.documentElement.lang = getLang();
initSession();

const app = createApp(App).use(VueQueryPlugin, { queryClient }).use(router).directive('focus', vFocus);
app.mount('#root');
// ভুলের খবর (Sentry) — DSN থাকলে তবেই, আলাদা অংশে লোড
void initSentry(app);
