import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

// অ্যাডমিন প্যানেল: আলাদা বিল্ড, ঠিকানা /admin/ (server/public/admin-এর জায়গায়)।
// প্যানেলের CSP কড়া (script-src 'self'; style-src 'self') — বিল্ডে শুধু বাইরের .js/.css ফাইল, ভেতরে লেখা স্ক্রিপ্ট/স্টাইল নেই।
export default defineConfig({
  root: fileURLToPath(new URL('./admin', import.meta.url)),
  base: '/admin/',
  publicDir: 'public',
  // শুধু render ফাংশন (SFC নেই) — Vue-র বাড়তি অংশ বাদ
  define: {
    __VUE_OPTIONS_API__: 'false',
    __VUE_PROD_DEVTOOLS__: 'false',
    __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false',
  },
  build: {
    outDir: fileURLToPath(new URL('./dist-admin', import.meta.url)),
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
    // ছোট ফাইলও data: URL নয় (CSP-তে font-src 'self')
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
  },
  server: {
    proxy: { '/api': { target: 'https://blood-server-ashy.vercel.app', changeOrigin: true } },
  },
});
