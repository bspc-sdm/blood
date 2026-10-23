// খোলস (AppShell) থেকে: লগইন থাকলে মেসেজ শোনা শুরু, লগআউটে সব মোছা; উপরের বার আর পাশের মেনুর ব্যাজ (না পড়া মোট)
import { computed, onBeforeUnmount, watch } from 'vue';
import { useSession } from '@/lib/session';
import { featureOn, initFeatures } from '@/lib/features';
import { chat, resetChat, startChat, stopChat } from './store';

export function useChatBadge() {
  const s = useSession();
  // ফিচার সুইচ জানা শুরু (একবারই; কলের অংশও ডাকে)
  initFeatures();
  watch(
    () => (s.ready && !s.guest && !s.deactivated && featureOn('chat') ? s.uid : null),
    (uid, old) => {
      if (uid) startChat(uid);
      else if (old) resetChat();
      else stopChat();
    },
    { immediate: true },
  );
  onBeforeUnmount(stopChat);
  return computed(() => (chat.me && featureOn('chat') && chat.unread > 0 ? chat.unread : null));
}
