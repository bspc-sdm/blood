# web-vue — port status

Vue 3 + TypeScript + Vite + Tailwind CSS v4 (no preflight) + Reka UI rewrite of `web/` (React). `web/` is untouched.

## Done
- Project setup: package.json, vite.config.ts (base `/w/`, build to `dist/`), tsconfig, index.html, public/ (manifest, icon, geo data copied).
- `src/lib`: copied unchanged (api, firebase, testmode, store, format, rules, labprice, geo, profile, districts-data, icons). Reactive ports: i18n (lang is a ref, so `tr()` updates templates), theme, care, query (`@tanstack/vue-query`, `useQ`/`useCall` return reactive objects), session (module singleton plus `initSession()`), nav.ts (React Router helpers: `useNav`, `useLoc`, `useSearchParams`, `matchPath`, `canGoBack`), uid.ts.
- Styles: tokens/base/layout and all section CSS copied as-is into one `@layer components` (main.css). Tailwind utilities sit in a later layer; theme colours are mapped to the CSS tokens.
- UI kit (`src/ui`): every component from `kit.tsx`, plus PlacePicker, AreaPicker and `useFilterArea`. Dialog uses Reka Dialog, PillTabs uses Reka Tabs.
- Shell: AppShell, ErrorBoundary, ListDetail, DetailPane, SubPageBar, Workspace, FormPage, chrome.ts, NotFound, router.ts (same URLs as App.tsx).
- Account core: LoginFlow, ProfileForm, BloodGroupChips; demo login role list and the switch-role dialog.
- **Blood section ported in full** (Home, Emergency, MyRequests, RequestDetail (guest and member), RequestForm (new and edit), all parts). Shots ≤0.4%.
- **Services section ported in full** (Directory, ambulance request flow, MedFind, Nearest, pharmacy send, facility/location pickers, fare). Shots ≤0.6%.
- **Account section ported in full** (Profile, EditProfile, Settings, Inbox, Help, Care, Login, dialogs). Shots 0% at mobile and desktop.
- **Appointment section ported in full** (Doctors list/detail/booking, HomeCare, MyAppointments, live serial queue, pro workspace /work/pro with bookings, patients, chamber setup, home-care stats). Shots 0% at mobile and desktop. `/work/pro` is wired in WorkSection; the rest of /work is still TODO.
- **Work section ported in full** (/work overview, add, ambulances + driver calls with fare offers, pharmacy info/finds/edit, blood banks, lab, BizHome redirects). Shots 0% at mobile and desktop.
- **rx/doctor ported in full** (/work/rx: patients + history, write with drug search and draft paper, list, view/print/cancel, report reviews and settings, team). Shots ≤0.06% desktop; mobile 1.27% on one page from pill-tab scroll position.
- **rx/partner ported in full** (pharmacy counter: code open, dispense, missed, online orders, shop QR + online settings, shop books: stock/buy/sales/analytics; lab: list, detail, report entry, photos, home-sample confirm, prices editor, own normal ranges). Shots 0% mobile, ≤0.05% desktop (rxpartner, shopbooks, work incl. pharmacy info page). `StockTab` (stock.tsx) is dead code in React and was not ported.
- **Admin panel ported in full** (`admin/`, build `npm run build:admin` → `dist-admin/`, base `/admin/`): all 18 nav pages plus verify, ticket and user detail, login with email code, idle logout warning, background count refresh, same hash routes (`#/pharmacies?s=approved`). Pages are TS render functions that mirror the old `h()` trees one to one, so the DOM and CSS (`admin/src/style.css`, copied) stay the same. The build has no inline script/style, so it works under the panel's strict CSP. Shots against the local stack (e2e/server/local.js + Auth emulator, demo seeded), original vs Vue under the same CSP: 0% on every page at 390px and 1440px except data drift (the admin log grows between runs). Write flows checked with `test/admin-smoke.mjs` (reject with reason, verify approve, ticket reply, catalog save, emergency create, refresh): all pass.
- **Health section ported in full** (Discussion, Tips, Badge, Prescriptions, RxActivity, common, qr). Shots 0% except the QR code (it encodes `location.origin`, and the two shot servers use different ports).
- `npx vue-tsc --noEmit -p tsconfig.app.json` passes. `vite build --mode test` works.
- `test/` holds the React fixtures, pages JSON, serve.mjs and shots.mjs, copied as-is.

## Tests and checks (all green)
- `npm run typecheck` (vue-tsc), `npm run lint` (ESLint flat config, Vue + TS), `npm run check:bangla` (every Bangla piece byte-for-byte from web/src or the old admin; reverse coverage 2510/2510 React pieces present in Vue), `npm run test:unit` (Vitest, 39 tests: i18n, nav/matchPath, rules/format, rx models, UI kit, admin helpers, admin action button).
- Playwright with the mocked API (`test/serve.mjs` + `test/fixtures` + `test/fixtures-e2e`): 234 tests — every page in `test/pages/*.json` (219), recent UX (ambulance call dialog, sticky pharmacy send + call, doctor chamber filters, my prescriptions, pharmacy tabs, foreign history entries), admin (login, 18 menu items, every page under CSP at mobile and desktop, list search/tabs/armed reject with reason, verify gating, catalog save, user detail tabs, logout).
- Real-stack journeys (`e2e/journeys.mjs`, unchanged `e2e/web/journeys`, local server + Auth emulator, demo seeded): Vue fails exactly the same scenarios as React (56 lines; the journeys predate recent React UI, e.g. "অ্যাপয়েন্টমেন্ট চাই" is now "এই চেম্বারে অ্যাপয়েন্টমেন্ট"). The two Vue-only failures (J-61/J-62, caregiver soft navigation) were fixed in `router.ts`.
- CI: `.github/workflows/web-vue.yml` (typecheck, lint, Bangla, unit, build web + admin, Playwright mocked); the real-stack journeys run only on manual dispatch.
- Screenshots React vs Vue (mobile + desktop): 518 pairs, 476 identical, 496 within 0.1%; the rest are the QR code, admin log data drift and capture timing. Pairs in the session scratchpad `vue-port/shots/`.

## Remaining (not done)
- Fix the stale `e2e/web/journeys` (they fail on React too) — outside web-vue, owner's call.
- Cutover (copy `dist/` → `server/public/w`, `dist-admin/` → `server/public/admin`, adjust `web.yml`) — owner's decision; nothing deployed.

## Notes / known issues
- Admin: the old panel sets a few `style` attributes (catalog and lab-test editor widths, `flex-wrap:nowrap`). Production's CSP blocks them (`style-src 'self'`, console shows "Refused to apply inline style"), so they never took effect. The Vue port leaves them out to match what users see.
- Pin `vue-tsc` to 3.2.4: 3.3.x wrongly reports `.value` errors for imported bindings used inside arrow functions in templates. The runtime code is fine.
- The React test build for comparison is made from a copy of web/ (node_modules symlinked from the main checkout). The `react-web`, `compare.py` and `shots.sh` scripts live outside the repo in the session scratchpad.
- Router: `router.afterEach` repairs history entries pushed by someone else (no `current`/`position`), which otherwise broke the next `router.push`.
- Dead React code not ported: `rx/partner/stock.tsx` (StockTab) and `services/ui.tsx` `NearestCall`.
- `@fontsource/*` is pinned to 5.1.0 (React's lockfile). 5.3.0 changes glyph widths slightly, which moves line breaks.
- Side nav: `aria-current` follows React Router NavLink (own path or below; `/` only exact), not the item's `match()`.
- Dialog.vue: `role`/`aria-labelledby` are bound only when set. Binding them as undefined removed Reka's `role=dialog`.
- React has no service worker and no FCM web push; only the manifest. Keep it that way for parity.
- Templates: never put whitespace between an icon and its text (React JSX had none). A leading/trailing space inside an element before/after `{{ }}` is dropped by Vue's whitespace condense; write `{{ ' ' + x }}` instead. Numeric inline styles need `'px'`. Use `canGoBack()` (vue-router `history.state.back`) instead of React Router's `idx`.

## Next steps
1. Owner review on branch `vue-web` (never merged to main).
2. If approved: cutover as described in README.md.
