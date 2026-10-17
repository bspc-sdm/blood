# Lifolo ওয়েব

অ্যাপের ওয়েব রূপ — মোবাইল, ট্যাবলেট আর ডেস্কটপের জন্য। একই সার্ভার (`server/`), একই Firebase অ্যাকাউন্ট আর নিয়ম।
বিল্ড সরাসরি `server/public/w/`-এ যায়; Vercel একই ঠিকানায় ওয়েব (`/`) আর API (`/api/*`) দেয়।

## চালানো

```bash
cd web
npm install
npm run dev          # লোকাল (API লাইভ সার্ভারে যায়)
npm run build        # টাইপ চেক + server/public/w-তে বিল্ড — কমিটের আগে চালান
```

## পরীক্ষা (আসল সার্ভার ছাড়া)

```bash
npx vite build --mode test --outDir test/.build/w --emptyOutDir
node test/shots.mjs test/pages/<অংশ>.json test/out/<অংশ>
```

- `test/fixtures/*.mjs` — `/api/*`-এর নকল উত্তর (`{ action: (body) => json }`)।
- `test/pages/*.json` — কোন পাতা, কোন অবস্থায় (লগইন, প্রোফাইল, Firestore ডক) ছবি তোলা হবে।
- তিন মাপে ছবি: মোবাইল ৩৯০, ট্যাবলেট ৮২০, ডেস্কটপ ১৪৪০। পাশে স্ক্রল, কনসোলের ভুল ধরা পড়ে (`problems.json`)।
- পরীক্ষার বিল্ডে `window.__lfTest = { user, profile, docs, queries }` দিলে লগইন করা অবস্থা দেখা যায় (`src/lib/testmode.ts`)।

## নকশা

| মাপ | খোলস |
|---|---|
| মোবাইল (< ৭২০) | অ্যাপের মতো: উপরে বার, নিচে ট্যাব; বিস্তারিত পাতা পুরো পর্দায়, "পেছনে" বারসহ |
| ট্যাবলেট (৭২০–১১৭৯) | বাঁয়ে সরু রেল; ৯৬০-এর বেশি হলে তালিকা + বিস্তারিত পাশাপাশি |
| ডেস্কটপ (≥ ১১৮০) | বাঁয়ে পুরো মেনু + তালিকা + বিস্তারিত (৩ কলাম) |

পাতার তিন ছাঁচ (`src/shell/templates.tsx`):

1. **ListDetail** — তালিকা + বিস্তারিত (হোম, সেবা, ডাক্তার, আলোচনা…)। বাছা জিনিস URL-এ থাকে (`/services/pharmacy/:id`)।
2. **Workspace** — চওড়া কাজের পাতা ("আমার কাজ": টেবিল, পরিসংখ্যান)।
3. **FormPage** — ফর্ম, মাঝখানে এক কলাম (রিকোয়েস্ট দেওয়া, ব্যবসা যোগ, প্রোফাইল)।

## নিয়ম

- সব লেখা `tr("English", "বাংলা")`, সংখ্যা `num()` — অ্যাপের মতো। পর্দায় অপ্রয়োজনীয় ব্যাখ্যা নয়।
- রং/মাপ শুধু `src/styles/tokens.css`-এর টোকেন থেকে (লাইট/ডার্ক দুটোতেই চলে)।
- সার্ভারের কাজ: `call('action', body)` বা `useCall(...)` (`src/lib/api.ts`, `src/lib/query.ts`)।
  লগইন লাগে এমন কাজের আগে `requireLogin(reason, then, { donor })` (`src/lib/session.tsx`)।
- ব্যবহারকারীর লেখা সবসময় React দিয়ে (কখনো `dangerouslySetInnerHTML` নয়)।
- আইকন অ্যাপের থেকেই: `python3 web/scripts/icons.py` (`<Icon name="call" />`)।
