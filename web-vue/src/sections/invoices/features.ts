// ফিচার সুইচ (মালিকের নিয়ম ২০২৬-১০-২৩: নতুন সব সুবিধা বন্ধ অবস্থায় আসে; backend/docs/FEATURES.md, lib/features.ts)।
// রসিদের চাবি: inv_pharmacy, inv_lab, inv_chamber, inv_ambulance, my_expenses (+ desktop — Windows অ্যাপের লিংক)।
// যাচাই পাতা /v/:code সবসময় খোলা। পরীক্ষার বিল্ডে window.__lfTest.features দিয়ে বন্ধ করা যায় (lib/features.ts মানে)।
import { featureOn } from '@/lib/features';

export type InvFeature = 'inv_pharmacy' | 'inv_lab' | 'inv_chamber' | 'inv_ambulance' | 'my_expenses' | 'desktop';

/** চাবি চালু কি না (reactive) */
export const invFeature = (key: InvFeature): boolean => featureOn(key);
