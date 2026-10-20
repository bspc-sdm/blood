# Test gaps: status (branch `test-gaps`, 2026-10-20)

## Done
- Web journeys now run against the **Vue web and admin plus in-CI Spring** (Postgres, Redis, Auth emulator, `PUSH_MODE=fake`) in `backend-e2e.yml`, which is now the default web e2e. React is still selectable (`web: react`). The old Node runner in `web-vue/e2e/journeys.mjs` is removed.
  - CI on `f4602d1`: **243/243 scenarios pass** across 13 journeys. The original 175 all pass on Vue+Spring.
- New journeys:
  - `09-ambulance-call`: the full "call an ambulance" flow. Quote, agree, escalation, by-phone, cancel, rating.
  - `10-admin-support`: tickets, admin add/remove, account disable, email-code signup, emergency appeal.
  - `11-notifications`: every push type is checked for title, body, data and deep-link target, plus device-token register/move/unregister and a DB audit.
  - `12-more-actions`: the remaining gap actions and the demo wipe.
- Actions covered end-to-end (successful call from a UI journey): **158 → 223 of 225**. Gap list: 65 of 67 covered. Only `phone_send_code` and `phone_verify_code` are left, and they are reachable only on the error path because phone login is disabled in config.
- Push types verified: **21/21**. That is 20 named types plus helper variants, 29 checks in total. The matrix is in `report-spring.md` and `push-matrix-spring.json`.
- Android (runs against production demo):
  - `e2e/android/notify.sh` replays each push's intent extras and checks the screen it opens: 23/23 pass.
  - New flows `j9-rx-online`, `j11-ambulance-driver` and `j12-appointments` pass.
  - Full run 39389313698: all 12 flows plus notify 23/23 green. `j10` hit the home load error again; it was captured as `j10d-bug-home-load-error.png` and then retried.
  - Regexes now match both Unicode forms of য়.

## Remaining / next steps
- Re-run Android after `app-fixes` (home load-error fix) lands on main.
- When web CI and Android are green on the same commit: rebase on `origin/main` and fast-forward `main`.
- Optional: an Android inbox screen check, and more of the 76 untested Android screens (admin tabs, health tabs, shop/lab edit).

## Known breakage / app bugs found (not fixed, tests record them)
1. **FIXED on this branch: web `amb_req_mine` deep link.** In `web-vue/src/sections/account/util.ts`, `inboxTarget` has no case for `amb_req_mine`. The inbox item "…৳N-তে আসতে রাজি" opens `/` instead of `/services/ambulance/call/<reqId>`. `inboxTarget` now routes it to the request page, with a unit test; journey 11 checks it strictly.
2. **Being fixed on `app-fixes`: Android home sometimes shows "রিকোয়েস্ট লোড হয়নি".** The server returns 200 at the same moment (probe.sh). Likely cause: in `MainScreen.kt:226`, `runCatching` swallows the `CancellationException` from a cancelled `LaunchedEffect`, sets `errorMsg`, and the newer successful load never clears it. `j10` takes a screenshot (`j10d-bug-home-load-error`) and then taps retry.
3. **Android badge doc request.** Tapping the "please send your badge document photos" notification opens Home. The web opens `/health/badge`.
4. **Android notification `appointment`.** On a notification tap the app ignores `data.role`, unlike the in-app inbox.
5. **Android types `badge`, `eligible`, `correction`, `hospital`, `request_removed`.** A tap on a running app (`onNewIntent`) does nothing.
6. **FIXED on main (7867a61): patient cancels after a driver quoted.** The quoting driver gets no push; only an accepted driver is notified.
7. **FIXED on main (04efb33): correction requests.** No client or endpoint can create a new one; only seeded rows exist.
8. **Untranslated text in Bangla mode.** `/rx/{code}` (fixed on main in c4e71840) shows the English line "Issued on Lifolo by a verified doctor…", and two admin pages show English seed text.
9. **Seeded demo order.** `demo-svc-rx-b` has an "on the way" order, so the patient can neither withdraw it nor re-send that prescription. Expected, but it is a dead end in the demo.
