<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { Button, IconButton } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { queryClient } from '@/lib/query';
import { KEY, type Nearby } from './data';
import { isActive } from './model';
import RequestInfo from './parts/RequestInfo.vue';
import Note from './parts/Note.vue';
import Disclaimer from './parts/Disclaimer.vue';
import ShareDialog from './parts/ShareDialog.vue';
import ShareCard from './ShareCard.vue';
import LoginNeeded from './LoginNeeded.vue';

const props = defineProps<{ id: string; title: string }>();
const s = useSession();
const sharing = ref(false);

/** অতিথির তালিকায় আগে আনা রিকোয়েস্ট (ক্যাশ থেকে; তালিকা পরে এলেও দেখায়) */
const tick = ref(0);
const unsub = queryClient.getQueryCache().subscribe(() => tick.value++);
onBeforeUnmount(unsub);
const hit = computed<Nearby | null>(() => {
  void tick.value;
  const all = queryClient.getQueriesData<Nearby[]>({ queryKey: [KEY, 'nearby'] });
  for (const [, list] of all) {
    const h = list?.find((x) => x.r.id === props.id);
    if (h) return h;
  }
  return null;
});
const active = computed(() => !!hit.value && isActive(hit.value.r));
const respond = () => s.requireLogin(tr('Log in to respond to this request', 'সাড়া দিতে লগইন করুন'), () => {}, { donor: true });
const report = () => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => {});
</script>

<template>
  <DetailPane v-if="!hit" :title="title" back-to="/">
    <LoginNeeded icon="bloodtype" :reason="tr('Log in to see and respond to this request', 'রিকোয়েস্টটা দেখতে ও সাড়া দিতে লগইন করুন')" />
  </DetailPane>
  <DetailPane v-else :title="title" back-to="/">
    <template #actions>
      <IconButton v-if="active" icon="share" :label="tr('Share', 'শেয়ার')" @click="sharing = true" />
      <IconButton icon="flag" :label="tr('Report', 'রিপোর্ট')" @click="report" />
    </template>
    <RequestInfo :r="hit.r" :km="hit.km" />
    <ShareCard v-if="active" :r="hit.r" @share="sharing = true" />
    <template v-if="active">
      <Button block icon="volunteer_activism" @click="respond">{{ tr("I'll donate", 'রক্ত দেব') }}</Button>
      <Note>{{ tr('Your name and number will be shared with the requester.', 'রক্তপ্রার্থী আপনার নাম ও নম্বর দেখতে পাবেন।') }}</Note>
    </template>
    <Disclaimer />
    <ShareDialog v-if="sharing" :r="hit.r" @close="sharing = false" />
  </DetailPane>
</template>
