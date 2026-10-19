<script setup lang="ts">
// সহকারী (শুধু ডাক্তার): ইমেইল যোগ/সরানো — অ্যাপের RxTeamDialog, প্যানেলের teamView (প্রতিটা বদল সাথে সাথে সেভ)।
import { computed, ref } from 'vue';
import { Button, ErrorBox, ErrorText, Icon, IconButton, SkeletonCards, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { MAX_ASSISTANTS } from './model';

const q = useCall<{ emails: string[] }>('rx_team');
const input = ref('');
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const emails = computed(() => q.data?.emails ?? []);
const full = computed(() => emails.value.length >= MAX_ASSISTANTS);

async function save(next: string[], what: string) {
  busy.value = what;
  err.value = null;
  try {
    await call<{ emails: string[] }>('rx_team_set', { emails: next });
    await q.refetch();
    toast(tr('Saved', 'সেভ হয়েছে'));
    return true;
  } catch (e) {
    err.value = errorText(e);
    return false;
  } finally {
    busy.value = null;
  }
}
async function add() {
  const v = input.value.trim().toLowerCase();
  if (!v.includes('@') || busy.value) return;
  if (emails.value.includes(v)) {
    input.value = '';
    return;
  }
  if (await save([...emails.value, v], '+')) input.value = '';
}
</script>

<template>
  <SkeletonCards v-if="q.isPending" :n="1" :h="220" />
  <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
  <div v-else class="rxd-narrow col gap-16">
    <section class="card col gap-12">
      <div class="row">
        <span class="rxd-ic"><Icon name="group_round" /></span>
        <div class="col gap-2 grow">
          <h2 class="t-title-m">{{ tr('Assistants', 'সহকারী') }}</h2>
          <span class="t-body-s muted num">{{ tr(`${emails.length} of ${MAX_ASSISTANTS}`, `${num(emails.length)}/${num(MAX_ASSISTANTS)} জন`) }}</span>
        </div>
      </div>
      <p class="t-body-m">
        {{ tr('Assistants log in with this email and enter patients. Only you write prescriptions.', 'সহকারী এই ইমেইলে লগইন করে রোগী এন্ট্রি করবেন। প্রেসক্রিপশন লিখবেন শুধু আপনি।') }}
        {{ tr(`Up to ${MAX_ASSISTANTS}.`, `সর্বোচ্চ ${num(MAX_ASSISTANTS)} জন।`) }}
      </p>
      <ul v-if="emails.length" class="col gap-2 rxd-emails">
        <li v-for="e in emails" :key="e" class="row rxd-email">
          <Icon name="mail" class="rxd-ic16" />
          <span class="grow t-body-l rxd-break">{{ e }}</span>
          <IconButton small icon="close" :label="tr('Remove', 'সরান') + ': ' + e" :disabled="!!busy" @click="save(emails.filter((x) => x !== e), e)" />
        </li>
      </ul>
      <p v-else class="t-body-m muted">{{ tr('No assistants', 'কোনো সহকারী নেই') }}</p>
      <form v-if="!full" class="row gap-8" @submit.prevent="add">
        <input
          class="input grow"
          type="email"
          inputmode="email"
          autocomplete="off"
          :value="input"
          :placeholder="tr('Assistant\'s email', 'সহকারীর ইমেইল')"
          :aria-label="tr('Assistant\'s email', 'সহকারীর ইমেইল')"
          @input="input = ($event.target as HTMLInputElement).value.trim().slice(0, 200)"
        />
        <Button type="submit" class="rxd-add-btn" :busy="busy === '+'" :disabled="!input.includes('@') || !!busy">{{ tr('Add', 'যোগ') }}</Button>
      </form>
      <ErrorText :text="err" />
    </section>
  </div>
</template>
