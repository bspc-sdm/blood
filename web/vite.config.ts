import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

// বিল্ড সরাসরি সার্ভারের public/w-তে যায়: Vercel একই ঠিকানায় ওয়েব আর API দেয়
// (আলাদা ডিপ্লয় বা আলাদা ডোমেইন লাগে না)। পাতার পথ (/, /services …) vercel.json-এ /w/index.html-এ যায়।
export default defineConfig({
  base: '/w/',
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: '../server/public/w',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  server: {
    // লোকাল চালালে API লাইভ সার্ভারে যায়
    proxy: { '/api': { target: 'https://blood-server-ashy.vercel.app', changeOrigin: true } },
  },
});
