<script setup lang="ts">
// অ্যাকাউন্ট মোছার শেষ ধাপ (delete_account {ack:true}) — /delete-account পাতার ভেতরে, ছোট ডায়ালগ নয়।
// "আমি বুঝেছি …" টিক না দিলে লাল বোতাম চাপা যায় না। সফল হলে deleted(deleteAt, email) পাঠিয়ে লগআউট করে।
// নতুন লেখা, তাই বাংলা লাইনে bn-ok।
import { ref } from 'vue';
import { Button, Check, ErrorText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { trPlain } from '@/lib/i18n';
import { useSession } from '@/lib/session';

const emit = defineEmits<{ deleted: [deleteAt: number | null, email: string] }>();
const s = useSession();
const understood = ref(false);
const deleting = ref(false);
const deleteError = ref<string | null>(null);

async function deleteAccount() {
  if (!understood.value || deleting.value) return;
  deleting.value = true;
  deleteError.value = null;
  const email = s.email;
  try {
    const r = await call<{ ok?: boolean; deleteAt?: number }>('delete_account', { ack: true });
    // আগে জানানো, তারপর লগআউট (লগআউটে এই অংশ সরে যায়, তখন আর emit যায় না)
    emit('deleted', typeof r?.deleteAt === 'number' ? r.deleteAt : null, email);
    await s.signOut().catch(() => {});
  } catch (e) {
    deleteError.value = errorText(e);
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <div class="col gap-12" data-testid="delete-confirm">
    <Check v-model="understood" :disabled="deleting" data-testid="delete-ack">
      {{ trPlain('I understand that after 30 days all my data is deleted for good.', 'আমি বুঝেছি, ৩০ দিন পর সব তথ্য চিরতরে মুছে যাবে।') }}<!-- bn-ok -->
    </Check>
    <ErrorText :text="deleteError" />
    <div>
      <Button variant="danger" icon="delete" :disabled="!understood" :busy="deleting" @click="deleteAccount">{{ trPlain('Delete account', 'অ্যাকাউন্ট মুছুন') }}</Button>
    </div>
  </div>
</template>
