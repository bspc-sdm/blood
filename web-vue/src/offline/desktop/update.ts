// আপডেট (Tauri updater): https://lifolo.com/download/desktop/latest.json — সই মিলিয়ে (minisign, ভার্সনসহ — পুরোনোতে ফেরা যায় না)।
// চালুর ১ মিনিট পর আর প্রতি ৬ ঘণ্টায় দেখা; নতুন থাকলে নোটিফিকেশন আর সেটিংসে "এখন আপডেট করুন"।
// অফলাইন রসিদ SQLite-এ থাকে, তাই আপডেটে কিছু হারায় না।
import { reactive } from 'vue';
import { relaunch } from '@tauri-apps/plugin-process';
import { check, type Update } from '@tauri-apps/plugin-updater';
import { T } from '../text';
import { notify } from './notify';

export const updateState = reactive({ checking: false, available: '' as string, installing: false, error: '', checkedAt: 0 });
let pending: Update | null = null;
let told = '';

export async function checkUpdate(manual = false): Promise<void> {
  if (updateState.checking || updateState.installing) return;
  updateState.checking = true;
  updateState.error = '';
  try {
    const u = await check({ timeout: 30_000 });
    updateState.checkedAt = Date.now();
    pending = u;
    updateState.available = u?.version ?? '';
    if (u && told !== u.version && !manual) {
      told = u.version;
      await notify('Lifolo', T.updateReady(u.version));
    }
  } catch (e) {
    if (manual) updateState.error = String(e);
  } finally {
    updateState.checking = false;
  }
}

export async function installUpdate(): Promise<void> {
  if (!pending) await checkUpdate(true);
  if (!pending) return;
  updateState.installing = true;
  try {
    await pending.downloadAndInstall();
    await relaunch();
  } catch (e) {
    updateState.error = String(e);
    updateState.installing = false;
  }
}

export function startUpdateChecks() {
  setTimeout(() => void checkUpdate(), 60_000);
  setInterval(() => void checkUpdate(), 6 * 3600_000);
}
