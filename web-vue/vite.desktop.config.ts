import { defineConfig, mergeConfig, type Plugin } from 'vite';
import base from './vite.config';

// ডেস্কটপ (Tauri) বিল্ড: ওয়েবের একই বিল্ড, শুধু শুরুর ফাইল আলাদা (src/offline/desktop/main.ts — অফলাইন, লক, ট্রে …)
// আর ফল desktop/dist/w-তে (পাতার ঠিকানা ওয়েবের মতোই /w/…; desktop/scripts/prepare-web.mjs index.html ওপরে কপি করে)।
//   npx vite build -c vite.desktop.config.ts
const desktopEntry: Plugin = {
  name: 'lifolo-desktop-entry',
  transformIndexHtml: { order: 'pre', handler: (html) => html.replace('/src/main.ts', '/src/offline/desktop/main.ts') },
};

export default mergeConfig(
  base,
  defineConfig({
    plugins: [desktopEntry],
    build: { outDir: '../desktop/dist/w', emptyOutDir: true },
  }),
);
