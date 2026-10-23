<script setup lang="ts">
// খোলস: মোবাইলে অ্যাপের মতো (উপরে বার, নিচে ট্যাব); ট্যাবলেটে বাঁয়ে সরু রেল; ডেস্কটপে বাঁয়ে পুরো মেনু।
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { Button, Icon, IconButton } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { setCareMode, useCareMode } from '@/lib/care';
import { chrome } from './chrome';
import { useNavItems } from './navItems';
import LoginFlow from '@/account/LoginFlow.vue';
import { useChatBadge } from '@/chat/badge';
import { featureOn } from '@/lib/features';

const s = useSession();
const route = useRoute();
const router = useRouter();
const care = useCareMode();
const items = useNavItems();
const chatUnread = useChatBadge();
const badgeText = (n: number | null | undefined) => (n ? num(n > 99 ? '99+' : n) : null);
const pathname = computed(() => route.path);
// React Router-এর NavLink: aria-current শুধু নিজের ঠিকানায় (বা তার ভেতরে) থাকলে — "/" শুধু হুবহু
const navActive = (to: string) => {
  const p = pathname.value.length > 1 ? pathname.value.replace(/\/+$/, '') : pathname.value;
  return to === '/' ? p === '/' : p === to || p.startsWith(to + '/');
};
const scrolled = ref(false);
const onScroll = () => (scrolled.value = window.scrollY > 4);
onMounted(() => {
  onScroll();
  window.addEventListener('scroll', onScroll, { passive: true });
});
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll));
// পাতা বদলালে উপরে; নিজের কাজ/প্রোফাইলে গেলে কেয়ারগিভার মোড বন্ধ (অন্যের হয়ে নিজের চেম্বার চালানো নয়)
watch(
  pathname,
  (p) => {
    window.scrollTo(0, 0);
    if (/^\/(work|profile|settings|care)(\/|$)/.test(p)) setCareMode(null);
  },
  { immediate: true },
);

const extra = computed(() =>
  [
    { to: '/messages', label: tr('Messages', 'মেসেজ'), icon: 'forum_outline', show: !s.guest && featureOn('chat'), badge: chatUnread.value }, // bn-ok
    { to: '/profile', label: tr('Profile', 'প্রোফাইল'), icon: 'person_outline', show: !s.guest },
    { to: '/settings', label: tr('Settings', 'সেটিংস'), icon: 'settings_outline', show: true },
    { to: '/help', label: tr('Help', 'সাহায্য'), icon: 'help', show: true },
  ].filter((e) => e.show),
);
const photoSrc = computed(() => {
  const photo = s.profile?.photo;
  return photo ? (photo.startsWith('data:') ? photo : `data:image/jpeg;base64,${photo}`) : null;
});
</script>

<template>
  <div :class="['shell', chrome.detailOpen > 0 && 'detail-open', chrome.subOpen > 0 && 'sub-open']">
    <aside class="side-nav" :aria-label="tr('Main menu', 'মূল মেনু')">
      <RouterLink to="/" class="logo" aria-label="Lifolo">
        <span class="logo-mark"><Icon name="bloodtype" /></span>
        <span class="logo-name">Lifolo</span>
      </RouterLink>
      <nav class="col gap-4">
        <RouterLink v-for="it in items" :key="it.to" :to="it.to" class="nav-link" :aria-current="navActive(it.to) ? 'page' : undefined">
          <span class="ico"><Icon :name="it.match(pathname) ? it.iconOn : it.icon" /></span>
          <span class="label">{{ it.label }}</span>
        </RouterLink>
      </nav>
      <div class="nav-bottom">
        <div class="nav-sep" />
        <RouterLink v-for="e in extra" :key="e.to" :to="e.to" class="nav-link" :aria-current="navActive(e.to) ? 'page' : undefined">
          <span class="ico"><Icon :name="e.icon" /><span v-if="'badge' in e && e.badge" class="badge-dot err" data-testid="nav-chat-badge">{{ badgeText(e.badge) }}</span></span>
          <span class="label">{{ e.label }}</span>
        </RouterLink>
        <p class="nav-extra">{{ tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা') }}</p>
      </div>
    </aside>
    <div class="frame">
      <header :class="['topbar main-bar', scrolled && 'scrolled']">
        <RouterLink to="/" class="logo" aria-label="Lifolo">
          <span class="logo-mark"><Icon name="bloodtype" /></span>
          <span class="logo-name">Lifolo</span>
        </RouterLink>
        <h1 class="page-title ellipsis">{{ chrome.title }}</h1>
        <div v-if="!s.ready" class="top-actions" />
        <div v-else-if="s.guest" class="top-actions">
          <IconButton icon="settings_outline" :label="tr('App settings', 'অ্যাপ সেটিংস')" @click="router.push('/settings')" />
          <Button variant="tonal" class="login-btn" @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
        </div>
        <div v-else class="top-actions">
          <IconButton v-if="featureOn('chat')" icon="forum_outline" :label="tr('Messages', 'মেসেজ')" :badge="badgeText(chatUnread)" class="chat-nav" data-testid="top-chat" @click="router.push('/messages')" /><!-- bn-ok -->
          <IconButton icon="notifications_round" :label="tr('Notifications', 'নোটিফিকেশন')" :badge="s.me?.unread || null" class="bell" @click="router.push('/inbox')" />
          <button type="button" class="icon-btn" :aria-label="tr('Menu and profile', 'মেনু ও প্রোফাইল')" @click="router.push('/profile')">
            <span class="menu-avatar">
              <span class="pic"><img v-if="photoSrc" :src="photoSrc" alt="" /><Icon v-else name="person_filled" /></span>
              <span class="ham"><Icon name="menu_round" /></span>
            </span>
          </button>
        </div>
      </header>
      <!-- কেয়ারগিভার মোডে উপরে ফিতা: কার হয়ে কাজ করছেন -->
      <div v-if="care" class="row" :style="{ background: 'var(--tertiary-container)', color: 'var(--on-tertiary-container)', padding: '8px 16px' }">
        <Icon name="group_round" :size="20" />
        <span class="grow t-label-l">{{ tr(`Acting for ${care.name}`, `${care.name}-এর হয়ে কাজ করছেন`) }}</span>
        <Button variant="text" small @click="setCareMode(null)">{{ tr('Stop', 'বন্ধ করুন') }}</Button>
      </div>
      <main id="main" class="content"><slot /></main>
    </div>
    <nav :class="['bottom-nav', items.length > 4 && 'five']" :aria-label="tr('Main menu', 'মূল মেনু')">
      <RouterLink v-for="it in items" :key="it.to" :to="it.to" :aria-current="it.match(pathname) ? 'page' : undefined">
        <span class="ind" />
        <span class="sp" />
        <Icon :name="it.match(pathname) ? it.iconOn : it.icon" />
        <small>{{ it.label }}</small>
        <span class="sp" />
      </RouterLink>
    </nav>
    <LoginFlow v-if="s.pending" />
    <div v-if="s.disabledMessage" class="overlay" role="alertdialog" aria-modal="true">
      <div class="dialog small">
        <h2 class="t-title-l">{{ tr('Logged out', 'লগআউট হয়েছে') }}</h2>
        <p class="t-body-m pre">{{ s.disabledMessage }}</p>
        <div class="dialog-actions"><Button @click="s.clearDisabled()">{{ tr('OK', 'ঠিক আছে') }}</Button></div>
      </div>
    </div>
  </div>
</template>
