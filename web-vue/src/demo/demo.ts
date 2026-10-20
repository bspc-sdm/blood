// ===== ডেমো (শুধু পরীক্ষার জন্য) — ডেমো মুছলে এই ফোল্ডার আর LoginFlow/Profile-এর "ডেমো" লাইনগুলো মুছবেন =====
export const isDemoUid = (uid?: string | null) => !!uid && uid.startsWith('demo-');
