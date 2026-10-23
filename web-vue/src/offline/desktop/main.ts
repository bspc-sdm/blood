// ডেস্কটপ বিল্ডের শুরু (vite.desktop.config.ts index.html-এ /src/main.ts-এর জায়গায় এটা বসায়):
// ১) /api/… ডাক Rust-এর HTTP দিয়ে  ২) পুরো ওয়েব (main.ts)  ৩) অফলাইন, লক, ট্রে, আপডেট
import './net-install';
import '@/main';
import { bootDesktop } from './boot';

void bootDesktop();
