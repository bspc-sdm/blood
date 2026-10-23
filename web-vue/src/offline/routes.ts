// অফলাইন/ডেস্কটপের পাতা (main.ts একবার import করে): /desktop-app — Windows অ্যাপ নামানো (ফিচার সুইচ `desktop`)
// ডেস্কটপ সেটিংস (/desktop/settings) শুধু ডেস্কটপ বিল্ডে যোগ হয় (desktop/boot.ts)।
import { router } from '@/router';

router.addRoute({ path: '/desktop-app', component: () => import('./DesktopDownload.vue') });
