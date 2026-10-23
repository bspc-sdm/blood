// "আজকের কাজ"-এর কয়েকটা আইকন (Material Icons, Apache 2.0) — অ্যাপের আইকনের তালিকায় (icons/paths.ts, বানানো ফাইল) নেই
import { ICONS } from '@/icons/paths';

const V = '0 0 24 24';
const TODAY_ICONS: Record<string, [string, (string | [string, string])[]]> = {
  pause: [V, ['M6 19h4V5H6v14zm8-14v14h4V5h-4z']],
  today: [V, ['M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zM7 10h5v5H7z']],
  radio_button_unchecked: [V, ['M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z']],
};
for (const [k, v] of Object.entries(TODAY_ICONS)) if (!ICONS[k]) ICONS[k] = v;
