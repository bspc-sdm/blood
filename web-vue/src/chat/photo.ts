// মেসেজের ছবি: পাঠানোর আগে এই ব্রাউজারেই ছোট করা — লম্বা দিক সর্বোচ্চ ১৬০০ পিক্সেল, JPEG,
// base64 ১ MB-র (সার্ভারের সীমা) অনেক নিচে না আসা পর্যন্ত মান/মাপ কমানো। ফেরত: শুধু base64 (data: ছাড়া)।
import { T } from './text';

/** base64-এর সর্বোচ্চ দৈর্ঘ্য (অক্ষর) — ১ MB JPEG-এর base64 প্রায় ১.৩৩ MB; নিরাপদে নিচে রাখা */
export const PHOTO_MAX_B64 = 900 * 1024;

export interface Shrink { maxSide: number; quality: number }
/** পরের চেষ্টা: আগে মান কমে (০.৮ → ০.৫), তারপর মাপ — শেষ হলে null */
export function nextShrink(s: Shrink): Shrink | null {
  if (s.quality > 0.55) return { ...s, quality: Math.round((s.quality - 0.1) * 100) / 100 };
  if (s.maxSide > 800) return { maxSide: s.maxSide - 300, quality: 0.7 };
  return null;
}

async function decode(file: Blob): Promise<{ src: CanvasImageSource; w: number; h: number }> {
  try {
    const b = await createImageBitmap(file, { imageOrientation: 'from-image' });
    return { src: b, w: b.width, h: b.height };
  } catch {
    const url = URL.createObjectURL(file);
    try {
      const img = await new Promise<HTMLImageElement>((ok, no) => {
        const i = new Image();
        i.onload = () => ok(i);
        i.onerror = () => no(new Error(T.photoFailed()));
        i.src = url;
      });
      return { src: img, w: img.naturalWidth, h: img.naturalHeight };
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  }
}

export async function compressPhoto(file: Blob, maxB64 = PHOTO_MAX_B64): Promise<string> {
  if (file.type && !file.type.startsWith('image/')) throw new Error(T.photoFailed());
  const { src, w, h } = await decode(file);
  if (!w || !h) throw new Error(T.photoFailed());
  let s: Shrink | null = { maxSide: 1600, quality: 0.8 };
  while (s) {
    const scale = Math.min(1, s.maxSide / Math.max(w, h));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w * scale));
    c.height = Math.max(1, Math.round(h * scale));
    const ctx = c.getContext('2d');
    if (!ctx) throw new Error(T.photoFailed());
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.drawImage(src, 0, 0, c.width, c.height);
    const url = c.toDataURL('image/jpeg', s.quality);
    const b64 = url.slice(url.indexOf(',') + 1);
    if (b64.length <= maxB64) return b64;
    s = nextShrink(s);
  }
  throw new Error(T.photoBig());
}

/** base64 বা লিংক → <img src> */
export const imgSrc = (v: string) => (/^(data:|https?:|\/|blob:)/.test(v) ? v : `data:image/jpeg;base64,${v}`);
