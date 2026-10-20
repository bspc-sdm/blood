// ESLint (flat): Vue 3 + TypeScript-এর সুপারিশ করা নিয়ম। দেখতে কেমন (ফাঁকা, ক্রম) — সেটা নয়, ভুল ধরা।
import pluginVue from 'eslint-plugin-vue';
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript';

export default defineConfigWithVueTs(
  { ignores: ['dist/**', 'dist-admin/**', 'test/.build/**', 'test/out/**', 'test/admin-out/**', 'e2e/.build/**', 'node_modules/**', 'playwright-report/**', 'test-results/**'] },
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    rules: {
      // সার্ভারের উত্তর, React থেকে আনা টাইপ — কিছু জায়গায় any লাগে
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', destructuredArrayIgnorePattern: '^_' }],
      // পাতার নাম এক শব্দের (AccountSection নয়, যেমন Panel, Flash) — React-এর নামই রাখা
      'vue/multi-word-component-names': 'off',
    },
  },
  {
    files: ['test/**/*.mjs', 'e2e/**/*.mjs', 'scripts/**/*.mjs'],
    languageOptions: { globals: { process: 'readonly', console: 'readonly', fetch: 'readonly', URL: 'readonly', setTimeout: 'readonly', Buffer: 'readonly' } },
  },
);
