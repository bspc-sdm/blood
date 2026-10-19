import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';

// React ওয়েবের (web/) একই ঠিকানা: পাতা /w/-এর নিচে (Vercel /, /services … পথ /w/index.html-এ পাঠায়)।
// বিল্ড এখানে dist/-এ যায় — React বদলে এটা চালু করার (cutover) দিন dist/ server/public/w-তে বসবে।
export default defineConfig({
  base: '/w/',
  plugins: [vue(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // বড় লাইব্রেরি আলাদা ফাইলে — আমাদের কোড বদলালেও ব্রাউজারে আগেরগুলো জমা থাকে
        manualChunks: {
          firebase: ['firebase/app', 'firebase/auth'],
          vue: ['vue', 'vue-router', '@tanstack/vue-query'],
        },
      },
    },
  },
  server: {
    // লোকাল চালালে API লাইভ সার্ভারে যায়
    proxy: { '/api': { target: 'https://blood-server-ashy.vercel.app', changeOrigin: true } },
  },
});
