// শুধু পরীক্ষার বিল্ডে (vite build --mode test): আসল Firebase ছাড়াই লগইন করা অবস্থা দেখা যায়।
// আসল বিল্ডে (production) TEST_MODE মিথ্যা, তাই এই অংশ বাদ পড়ে যায়।
export const TEST_MODE = import.meta.env.MODE === 'test';

declare global {
  interface Window {
    /** Playwright বসায়: { user?: {uid,email}, profile?: {...}, docs?: { 'requests/abc': {...} }, queries?: { 'requests?requesterUid=u1': [...] } } */
    __lfTest?: { user?: { uid: string; email: string }; profile?: Record<string, unknown>; account?: Record<string, unknown>; docs?: Record<string, any>; queries?: Record<string, any[]> };
  }
}

export function testUser() {
  if (!TEST_MODE) return null;
  const u = window.__lfTest?.user;
  return u ? { ...u, getIdToken: async () => 'test-token' } : null;
}
/** পরীক্ষায় সার্ভারের বদলে একটা ডক (path যেমন "requests/abc") */
export function testDoc(path: string): any | undefined {
  return TEST_MODE ? window.__lfTest?.docs?.[path] : undefined;
}
/** পরীক্ষায় সার্ভারের তালিকার বদলে কোয়েরির ফল (চাবি নিজের মতো, যেমন "requests?requesterUid=u1") */
export function testQuery(key: string): any[] | undefined {
  return TEST_MODE ? window.__lfTest?.queries?.[key] : undefined;
}
