// ESC/POS: সাদা-কালো ছবি → থার্মাল প্রিন্টারের বাইট (GS v 0 raster)। বাংলা লেখা আগেই ছবিতে আঁকা (raster.ts) —
// প্রিন্টারের নিজের ফন্টে বাংলা নেই, তাই পুরো রসিদটাই একটা ছবি।

export interface Mono {
  width: number;
  height: number;
  /** প্রতি সারিতে ceil(width/8) বাইট, বাঁ দিকের পিক্সেল বড় বিটে, 1 = কালো */
  bits: Uint8Array;
}

/** RGBA → সাদা-কালো (আলো < threshold হলে কালো; স্বচ্ছ = সাদা) */
export function toMono(rgba: Uint8ClampedArray | Uint8Array, width: number, height: number, threshold = 160): Mono {
  const bpr = Math.ceil(width / 8);
  const bits = new Uint8Array(bpr * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const a = rgba[i + 3] / 255;
      const lum = (0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2]) * a + 255 * (1 - a);
      if (lum < threshold) bits[y * bpr + (x >> 3)] |= 0x80 >> (x & 7);
    }
  }
  return { width, height, bits };
}

/** নিচের সাদা অংশ ছেঁটে ফেলা (কাগজ বাঁচে) */
export function trimBottom(m: Mono, keep = 8): Mono {
  const bpr = Math.ceil(m.width / 8);
  let last = m.height - 1;
  while (last > 0 && m.bits.subarray(last * bpr, (last + 1) * bpr).every((b) => b === 0)) last--;
  const h = Math.min(m.height, last + 1 + keep);
  return { width: m.width, height: h, bits: m.bits.slice(0, h * bpr) };
}

export interface EscOptions {
  /** কাগজ কাটা (অটো-কাটার থাকলে) */
  cut?: boolean;
  /** ক্যাশ ড্রয়ার খোলা (ESC p) */
  drawer?: boolean;
  /** শেষে কত লাইন ফাঁকা */
  feed?: number;
  /** এক টুকরোয় কত সারি — ছোট বাফারের প্রিন্টারের জন্য */
  band?: number;
}

export function escpos(img: Mono, opts: EscOptions = {}): Uint8Array {
  const bpr = Math.ceil(img.width / 8);
  const band = Math.max(1, Math.min(opts.band ?? 128, 255));
  const out: number[] = [0x1b, 0x40]; // ESC @ — শুরু থেকে
  if (opts.drawer) out.push(0x1b, 0x70, 0x00, 0x19, 0xfa); // ESC p 0 25 250
  out.push(0x1b, 0x61, 0x01); // মাঝখানে
  for (let y0 = 0; y0 < img.height; y0 += band) {
    const h = Math.min(band, img.height - y0);
    out.push(0x1d, 0x76, 0x30, 0x00, bpr & 0xff, (bpr >> 8) & 0xff, h & 0xff, (h >> 8) & 0xff); // GS v 0 m xL xH yL yH
    const part = img.bits.subarray(y0 * bpr, (y0 + h) * bpr);
    for (let i = 0; i < part.length; i++) out.push(part[i]);
  }
  out.push(0x1b, 0x64, Math.max(0, Math.min(opts.feed ?? 4, 255))); // ESC d n
  if (opts.cut !== false) out.push(0x1d, 0x56, 0x42, 0x00); // GS V 66 0 — খাওয়ানোর পর আংশিক কাটা
  return Uint8Array.from(out);
}

/** কাগজের মাপ → ছাপার চওড়া (২০৩ dpi): ৫৮ মিমি = ৩৮৪, ৮০ মিমি = ৫৭৬ বিন্দু */
export const PAPER_DOTS: Record<'58' | '80', number> = { '58': 384, '80': 576 };
