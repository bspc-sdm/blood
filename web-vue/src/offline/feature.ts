// ফিচার সুইচ `desktop` (flags CONTRACT): ডাউনলোড লিংক আর নতুন কম্পিউটার নিবন্ধন শুধু চালু থাকলে।
// web-vue/src/lib/features.ts (flags এজেন্টের) থাকলে তার featureOn('desktop'); না থাকা পর্যন্ত নিজে `features` কাজটা ডেকে দেখে।
// import.meta.glob: ফাইল না থাকলে খালি — বিল্ড ভাঙে না।
import { ref } from 'vue';
import { call } from '@/lib/api';

type FeaturesModule = { featureOn?: (key: string) => boolean; refreshFeatures?: () => Promise<unknown> };
const mods = import.meta.glob('../lib/features.ts', { eager: true }) as Record<string, FeaturesModule>;
const lib: FeaturesModule | undefined = Object.values(mods)[0];

const own = ref(false);
let loaded = 0;

async function loadOwn() {
  if (Date.now() - loaded < 60_000) return;
  loaded = Date.now();
  try {
    const r = await call<{ keys?: Record<string, boolean> }>('features');
    own.value = !!r?.keys?.desktop;
  } catch {
    /* আগের মান থাকে (ডিফল্ট বন্ধ) */
  }
}

/** reactive: ডেস্কটপ এই ব্যবহারকারীর জন্য চালু কি না */
export function desktopFeatureOn(): boolean {
  if (lib?.featureOn) return lib.featureOn('desktop');
  void loadOwn();
  return own.value;
}

export async function refreshDesktopFeature(): Promise<boolean> {
  if (lib?.refreshFeatures) {
    await lib.refreshFeatures().catch(() => {});
    return !!lib.featureOn?.('desktop');
  }
  loaded = 0;
  await loadOwn();
  return own.value;
}
