// Firebase: অ্যাপের একই প্রজেক্ট — শুধু লগইন (Auth)। ডাটাবেস আর সরাসরি নয়, সব সার্ভার দিয়ে (lib/api.ts)।
// লগইন সার্ভারের ইমেইল কোড দিয়ে (custom token) — পাসওয়ার্ড বা গুগল পপআপ নেই, তাই iframe/পপআপ লাগে না।
import { initializeApp } from 'firebase/app';
import { browserLocalPersistence, indexedDBLocalPersistence, initializeAuth } from 'firebase/auth';

const app = initializeApp({
  // অ্যাপের google-services.json-এর একই চাবি (এটা গোপন নয়; সুরক্ষা আসে সার্ভার থেকে)
  apiKey: 'AIzaSyCSsc8_CIFPkGJIqUDUVeGLuCZ31yNh5Uk',
  authDomain: 'blood-3eb28.firebaseapp.com',
  projectId: 'blood-3eb28',
  storageBucket: 'blood-3eb28.firebasestorage.app',
  messagingSenderId: '634868254131',
});

export const auth = initializeAuth(app, { persistence: [indexedDBLocalPersistence, browserLocalPersistence] });
