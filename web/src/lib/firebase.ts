// Firebase: অ্যাপের একই প্রজেক্ট — শুধু লগইন (Auth)। ডাটাবেস আর সরাসরি নয়, সব সার্ভার দিয়ে (lib/api.ts)।
// লগইন সার্ভারের ইমেইল কোড দিয়ে (custom token) — পাসওয়ার্ড বা গুগল পপআপ নেই, তাই iframe/পপআপ লাগে না।
import { initializeApp } from 'firebase/app';
import { browserLocalPersistence, connectAuthEmulator, indexedDBLocalPersistence, initializeAuth } from 'firebase/auth';

const app = initializeApp({
  // অ্যাপের google-services.json-এর একই চাবি (এটা গোপন নয়; সুরক্ষা আসে সার্ভার থেকে)
  apiKey: 'AIzaSyCSsc8_CIFPkGJIqUDUVeGLuCZ31yNh5Uk',
  authDomain: 'blood-3eb28.firebaseapp.com',
  projectId: 'blood-3eb28',
  storageBucket: 'blood-3eb28.firebasestorage.app',
  messagingSenderId: '634868254131',
});

export const auth = initializeAuth(app, { persistence: [indexedDBLocalPersistence, browserLocalPersistence] });

// শুধু লোকাল পরীক্ষার বিল্ডে (VITE_AUTH_EMULATOR দিয়ে বানানো, e2e/web): আসল Firebase-এর বদলে Auth emulator।
// "origin" = একই ঠিকানা (পরীক্ষার সার্ভার emulator-এ পাঠিয়ে দেয়, তাই CSP বদলাতে হয় না)।
// আসল বিল্ডে এই চলক থাকে না, তাই Vite পুরো অংশটা বাদ দেয়।
const EMU = import.meta.env.VITE_AUTH_EMULATOR as string | undefined;
if (EMU) connectAuthEmulator(auth, EMU === 'origin' ? location.origin : EMU, { disableWarnings: true });
