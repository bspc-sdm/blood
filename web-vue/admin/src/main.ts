// অ্যাডমিন প্যানেল (Vue) — /admin/ (server/public/admin-এর জায়গায় বসবে)
import { createApp } from 'vue';
// Anek Bangla: বাংলা + ইংরেজি (unicode-range ধরে যে অংশ লাগে শুধু সেটা নামে)
import '@fontsource/anek-bangla/400.css';
import '@fontsource/anek-bangla/600.css';
import '@fontsource/anek-bangla/700.css';
import './style.css';
import App from './App';

createApp(App).mount('#root');
