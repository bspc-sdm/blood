// ফিচার সুইচ (মালিকের নিয়ম, ২৩ অক্টোবর ২০২৬): নতুন সব কিছু বন্ধ অবস্থায় যায়, অ্যাডমিন থেকে চালু হয়।
// online_chamber — অনলাইন চেম্বারের সেটআপ, ব্যাজ/ছাঁকনি, বুকিং, ফির অংশ; appt_video — "ভিডিও কলে যুক্ত হোন" (online_chamber-ও লাগে)।
// চালু না হলে (বা জানা না গেলে) বন্ধ — পাতা আগের মতো।
// একটাই ফিচার-সুইচের API — lib/features.ts (featureOn, refreshFeatures, setFeaturesForTest); এখানে শুধু আবার দেওয়া।
import { featureOn } from '@/lib/features';
export { featureOn, refreshFeatures, setFeaturesForTest } from '@/lib/features';

export const ONLINE_CHAMBER = 'online_chamber';
export const APPT_VIDEO = 'appt_video';
export const onlineOn = () => featureOn(ONLINE_CHAMBER);
export const videoOn = () => onlineOn() && featureOn(APPT_VIDEO);
