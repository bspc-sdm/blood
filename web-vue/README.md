# Lifolo ওয়েব (Vue)

`web/`-এর (React) পুরো ওয়েব আর `server/public/admin`-এর অ্যাডমিন প্যানেল নতুন করে লেখা — **Vue 3 + TypeScript + Vite**, স্টাইলে **Tailwind CSS v4 + Reka UI** (মালিকের সিদ্ধান্ত; অন্য কোনো UI কিট নয়)।
পাতা, ঠিকানা (URL), লেখা, দেখতে কেমন, সার্ভারের সাথে কথা (`/api/community`-এর action) — সব React-এর মতোই। **React-এর কোডই নিয়ম**: কোনো কিছু নিয়ে সন্দেহ হলে `web/src` যা করে, এখানেও তাই।

`web/` আর `server/public/admin` বদলানো হয়নি — বদলের দিন (cutover) পর্যন্ত ওগুলোই চালু।

## কী আছে

| ফোল্ডার | কী |
|---|---|
| `src/` | ওয়েব (ঠিকানা `/w/…`, Vercel বাকি সব পথ `/w/index.html`-এ পাঠায়) |
| `src/lib` | সার্ভার, Firebase লগইন, ভাষা, তারিখ, নিয়ম — React-এর `lib` থেকে (ভাষা আর সেশন Vue-র ref) |
| `src/ui` | UI কিট (বোতাম, ডায়ালগ — Reka Dialog, ট্যাব — Reka Tabs …) |
| `src/shell` | কাঠামো: পাশের মেনু, নিচের মেনু, তালিকা-খুঁটিনাটি, রাউটার (`router.ts`, React-এর `App.tsx`-এর একই ঠিকানা) |
| `src/sections/*` | ভাগ: blood, services, appointment, health, account, work, rx/doctor, rx/partner (ফার্মেসি, প্যাথলজি, হিসাব-খাতা) |
| `src/styles` | React-এর CSS হুবহু (`@layer components`), Tailwind-এর ক্লাস `tw:` দিয়ে, preflight নেই |
| `admin/` | অ্যাডমিন প্যানেল — আলাদা বিল্ড, ঠিকানা `/admin/` (১৮টা পাতা, যাচাই, আপিল, ব্যবহারকারী, ডেমো) |
| `test/` | স্ক্রিনশট (`shots.mjs`), নকল সার্ভার (`serve.mjs` + `fixtures/`), পাতার তালিকা (`pages/*.json`), অ্যাডমিনের তুলনা |
| `tests/unit/` | ছোট পরীক্ষা (Vitest) |
| `e2e/` | Playwright: নকল API-র সাথে (`*.spec.ts`), আর আসল সার্ভারের পুরো যাত্রা (`journeys.mjs`) |
| `scripts/check-bangla.py` | বাংলা লেখা হুবহু কি না |

## চালানো

```bash
npm ci
npm run dev            # ওয়েব, http://localhost:5173/w/ (API লাইভ সার্ভারে যায়)
npm run dev:admin      # অ্যাডমিন, http://localhost:5173/admin/
npm run build          # টাইপ চেক + ওয়েব (dist/) + অ্যাডমিন (dist-admin/)
```

## পরীক্ষা

```bash
npm run typecheck      # vue-tsc
npm run lint           # ESLint (Vue + TypeScript)
npm run check:bangla   # বাংলা লেখা React/অ্যাডমিনের সাথে বাইট ধরে একই কি না (দুই দিকেই)
npm run test:unit      # Vitest
npm run build:test && npm run build:admin
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers npx playwright test   # নকল API-র সাথে: সব পাতা, সাম্প্রতিক UX, অ্যাডমিন
```

- **সব পাতা** (`e2e/pages.spec.ts`): `test/pages/*.json`-এর প্রতিটা পাতা মোবাইল মাপে খোলে, JS-এর ভুল নেই, পাশে স্ক্রল নেই।
- **সাম্প্রতিক UX** (`e2e/ux.spec.ts`): অ্যাম্বুলেন্স ডাকুন (গাড়ির ধরন, ভাড়ার সীমা), ফার্মেসির নিচে আটকানো "প্রেসক্রিপশন পাঠান" আর পাশে ফোন, ডাক্তারের চেম্বার আর আজ/আগামীকাল ফিল্টার, আমার প্রেসক্রিপশন, ফার্মেসির ট্যাব।
- **অ্যাডমিন** (`e2e/admin.spec.ts`): লগইন (ইমেইল → কোড), ১৮টা মেনু, প্রতিটা পাতা কড়া CSP-তে, "নিশ্চিত? আবার চাপুন", যাচাইয়ে টিক ছাড়া অনুমোদন বন্ধ।

### আসল সার্ভারের পুরো যাত্রা

`e2e/web/journeys` (React-এর জন্য লেখা, বদল ছাড়া) Vue ওয়েব আর Vue অ্যাডমিনের ওপর — `e2e/server/local.js` (সার্ভারের আসল কোড, মেমোরির ডাটাবেস), Firebase Auth emulator, পরীক্ষার ঘড়ি (ঢাকা ১১:০০):

```bash
(cd ../server && npm ci) && (cd ../e2e && npm ci)
node e2e/journeys.mjs            # সব যাত্রা; বা যেমন: node e2e/journeys.mjs 03 05
WEB_BUILD=/path/to/react-build ADMIN=orig node e2e/journeys.mjs   # একই যাত্রা React-এ (তুলনার জন্য)
```

ফল `e2e/web/shots`-এ (`summary.json`, প্রতিটা ধাপের ছবি)।

### React বনাম Vue স্ক্রিনশট

`test/shots.mjs` একই `test/pages/*.json` আর একই নকল সার্ভার দিয়ে ছবি তোলে (মোবাইল ৩৯০, ডেস্কটপ ১৪৪০)। React-এর বিল্ড আর Vue-র বিল্ড আলাদা চালিয়ে পিক্সেল মেলানো হয়েছে।
অ্যাডমিন: লোকাল সার্ভার চালু রেখে `BASE=http://localhost:4610 node test/admin-shots.mjs <ফোল্ডার>` — পুরোনো আর Vue প্যানেল একই তথ্য আর একই CSP-তে; `node test/admin-smoke.mjs <ফোল্ডার>` লেখার কাজগুলো (বাতিল, যাচাই, উত্তর, সেভ, জরুরি আহ্বান) সত্যি সার্ভারে চালায়।

## নিয়ম (লেখার সময়)

- **বাংলা লেখা কপি করুন, টাইপ নয়।** "য়" দুইভাবে লেখা যায় (এক কোড পয়েন্ট বা য + ়) — দেখতে একই, মেলে না। `npm run check:bangla` ধরবে। একদম নতুন লেখা হলে লাইনে `bn-ok`।
- টেমপ্লেটে আইকন আর লেখার মাঝে ফাঁকা নয় (React-এ ছিল না)। ট্যাগের শুরুতে/শেষে `{{ }}`-এর পাশের ফাঁকা Vue বাদ দেয় — দরকার হলে `{{ ' ' + x }}`।
- `tr('English', 'বাংলা')` — ভাষা বদলালে নিজে থেকে নতুন করে আঁকে।
- সার্ভারের তথ্য `useQ`/`useCall` (`@tanstack/vue-query`, reactive)। React-এর ReactNode প্রপ → slot; ফাংশন প্রপ (onClick …) প্রপ হিসেবেই (আছে কি না দেখা যায়)।
- `@fontsource/*` 5.1.0-এ আটকানো (React-এর lockfile) — নতুন সংস্করণে অক্ষরের মাপ বদলে লাইন ভাঙে।
- `vue-tsc` 3.2.4-এ আটকানো — 3.3.x টেমপ্লেটের ভেতরের arrow ফাংশনে ভুল `.value` দেখায়।
- অ্যাডমিন প্যানেলের পাতাগুলো TypeScript-এর render ফাংশন (`admin/src/h.ts`-এর `h()`), পুরোনো `app.js`-এর গঠন হুবহু — তাই DOM আর CSS একই। ব্যবহারকারীর লেখা সবসময় লেখা হিসেবে বসে, কখনো HTML নয়।
- অ্যাডমিনের CSP কড়া (`script-src 'self'; style-src 'self'`): বিল্ডে শুধু বাইরের `.js/.css`, `style` অ্যাট্রিবিউট নয় (স্টাইল দরকার হলে Vue-র object style — CSSOM দিয়ে বসে, CSP আটকায় না)।

## বদলের দিন (cutover) — মালিক ঠিক করবেন

1. `npm run build` → `dist/`-এর সব `server/public/w/`-এ, `dist-admin/`-এর সব `server/public/admin/`-এ (`style.css`, `app.js`, `icons.js` আর লাগবে না)।
2. `vercel.json` বদলাতে হয় না (একই ঠিকানা, একই CSP)। `/r/`, `/rx/`, `/shop/` সার্ভারই বানায় — ওয়েব শুধু একই লিংক দেয়।
3. `web.yml`-এর "Committed build is up to date" ধাপ তখন `web-vue`-এর বিল্ডের সাথে মেলাতে হবে।

## জানা পার্থক্য

- পুরোনো অ্যাডমিনের কয়েকটা `style` অ্যাট্রিবিউট (বাছাইয়ের তালিকা আর রিপোর্টের ছকের ঘরের প্রস্থ) প্রোডাকশনের CSP আগে থেকেই আটকে দেয় (কনসোলে "Refused to apply inline style") — Vue-তে ওগুলো নেই, তাই দেখতে প্রোডাকশনের মতোই।
- React-এর মরা কোড আনা হয়নি: `rx/partner/stock.tsx` (StockTab), `services/ui.tsx`-এর `NearestCall` — কোথাও ব্যবহার হয় না।
- React-এ ওয়েব পুশ (FCM) বা service worker নেই, শুধু manifest — এখানেও তাই।
