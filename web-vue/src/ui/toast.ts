// টোস্ট — নিচে ছোট বার্তা, ৩.৫ সেকেন্ড
import { ref } from 'vue';

export const toastMessage = ref<string | null>(null);
let toastTimer: ReturnType<typeof setTimeout> | undefined;
export function toast(message: string) {
  toastMessage.value = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toastMessage.value = null;
  }, 3500);
}
