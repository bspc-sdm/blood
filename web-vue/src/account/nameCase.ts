/** ইংরেজি নামে প্রতিটা শব্দের প্রথম অক্ষর বড় */
export function nameCase(s: string) {
  return s
    .trim()
    .replace(/\s+/g, ' ')
    .split(' ')
    .map((w) => (w && /[a-z]/.test(w[0]) ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ')
    .replace(/(?<=[A-Za-z][-'’])([a-z])(?=[a-z])/g, (m) => m.toUpperCase());
}

export type ProfileMode = 'basic' | 'donor' | 'full';
