<script setup lang="ts">
// অ্যাকাউন্ট মোছার নিশ্চিতকরণ (delete_account) — প্রোফাইল আর পাবলিক /delete-account পাতা দুটোতেই একই ডায়ালগ।
// সফল হলে লগআউট করে deleted(deleteAt) পাঠায়; কোথায় যাবে তা যে খুলেছে সে ঠিক করে।
import { ref } from 'vue';
import { ConfirmDialog } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';

const emit = defineEmits<{ close: []; deleted: [deleteAt: number | null] }>();
const s = useSession();
const deleting = ref(false);
const deleteError = ref<string | null>(null);

async function deleteAccount() {
  deleting.value = true;
  deleteError.value = null;
  try {
    const r = await call<{ ok?: boolean; deleteAt?: number }>('delete_account');
    await s.signOut();
    emit('deleted', typeof r?.deleteAt === 'number' ? r.deleteAt : null);
  } catch (e) {
    deleteError.value = errorText(e);
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <ConfirmDialog
    :title="tr('Delete your account?', 'অ্যাকাউন্ট মুছবেন?')"
    :text="tr(
      'Your account will be deleted permanently after 30 days: profile, photo, phone number, requests, questions, answers, tips, appointments, badge and your businesses. Until then your listings and notifications are off and you are logged out everywhere. Log in again within 30 days to cancel and get everything back.',
      '৩০ দিন পর আপনার অ্যাকাউন্ট চিরতরে মুছে যাবে: প্রোফাইল, ছবি, ফোন নম্বর, রিকোয়েস্ট, প্রশ্ন, উত্তর, টিপস, অ্যাপয়েন্টমেন্ট, ব্যাজ আর আপনার ব্যবসা। এর মধ্যে আপনার তালিকা আর নোটিফিকেশন বন্ধ থাকবে, সব ফোন থেকে লগআউট হবে। ৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে।',
    )"
    :confirm="tr('Delete account', 'অ্যাকাউন্ট মুছুন')"
    danger
    :busy="deleting"
    :error="deleteError"
    @confirm="deleteAccount"
    @close="() => !deleting && emit('close')"
  />
</template>
