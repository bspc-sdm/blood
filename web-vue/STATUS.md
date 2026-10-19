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
- **Work section ported in full** (/work overview, add, ambulances + driver calls with fare offers, pharmacy info/finds/edit, blood banks, lab, BizHome redirects). Shots 0% except the pharmacy info page, which waits for rx/partner ShopPanels (stub for now). /work/rx, /work/pharmacy/orders, /work/pharmacy/shop/*, /work/lab/orders are stubs until rx is ported.
- **rx/doctor ported in full** (/work/rx: patients + history, write with drug search and draft paper, list, view/print/cancel, report reviews and settings, team). Shots ≤0.06% desktop; mobile 1.27% on one page from pill-tab scroll position.
- **Health section ported in full** (Discussion, Tips, Badge, Prescriptions, RxActivity, common, qr). Shots 0% except the QR code (it encodes `location.origin`, and the two shot servers use different ports).
- `npx vue-tsc --noEmit -p tsconfig.app.json` passes. `vite build --mode test` works.
- `test/` holds the React fixtures, pages JSON, serve.mjs and shots.mjs, copied as-is.

## Remaining (React files not yet ported; these sections are TODO stubs)
- rx/partner/* (pharmacy, lab, labdetail, labprices, labranges, dispense, online, orders, stock, common, model, shoppanels, books/*)
- Admin panel (`server/public/admin/app.js`, 18 pages including "ডেমো (পরীক্ষা)"): not started. Plan: a second Vite entry `admin/index.html`, same hash routes.
- Tests: Vitest unit tests, a Playwright port of `e2e/web/journeys`, the CI workflow `web-vue.yml`, the Bangla byte-check script (`scripts/check-bangla.py`, modelled on backend/scripts/check-bangla.py, using web/src as the corpus), and a Bangla README.
- Screenshot comparison: done for blood, services, health. Not yet run for the other sections.

## Notes / known issues
- Pin `vue-tsc` to 3.2.4: 3.3.x wrongly reports `.value` errors for imported bindings used inside arrow functions in templates. The runtime code is fine.
- The React test build for comparison is made from a copy of web/ (node_modules symlinked from the main checkout). The `react-web`, `compare.py` and `shots.sh` scripts live outside the repo in the session scratchpad.
- `@fontsource/*` is pinned to 5.1.0 (React's lockfile). 5.3.0 changes glyph widths slightly, which moves line breaks.
- Side nav: `aria-current` follows React Router NavLink (own path or below; `/` only exact), not the item's `match()`.
- Dialog.vue: `role`/`aria-labelledby` are bound only when set. Binding them as undefined removed Reka's `role=dialog`.
- React has no service worker and no FCM web push; only the manifest. Keep it that way for parity.
- Templates: never put whitespace between an icon and its text (React JSX had none). Numeric inline styles need `'px'`. Use `canGoBack()` (vue-router `history.state.back`) instead of React Router's `idx`.

## Next steps
1. Port the sections in order: work, rx/doctor, rx/partner. Then admin.
2. Run the React-vs-Vue shots for every `test/pages/*.json` at 390px and fix the differences.
3. Add the Bangla check script, Vitest, the Playwright e2e port, `.github/workflows/web-vue.yml` and README (bn).
