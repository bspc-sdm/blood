// লগইন করা মানুষের uid (ref) — ক্যাশের চাবিতে লাগে (React-এ প্রতি আঁকায় auth.currentUser পড়া হতো)
import { ref } from 'vue';

export const authUid = ref<string | null>(null);
