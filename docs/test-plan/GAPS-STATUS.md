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

## In-app calls + online video chambers (new feature, 2026-10-23, branch `docs-calls`)
Manual tests: [manual/17-calls.md](manual/17-calls.md). There are 88 tests: `M-CAL-01…65` for calls and `M-ONC-01…23` for online chambers. Of these, 21 are marked "(২টা আসল ফোন)". The total is about 8 h.
Security and abuse review: [CALLS-SECURITY-REVIEW.md](CALLS-SECURITY-REVIEW.md). It lists 14 items: 6 High, 6 Medium and 2 Low.
The file is listed in the [04-manual-tests.md](04-manual-tests.md) index. Its tests are split over release phases 2, 3, 6 and 7 in [phases.json](phases.json).

| Feature area | Manual | Automated today | Gap / to automate |
|---|---|---|---|
| Who may call (relations, windows, demo↔real, deactivated) | M-CAL-06, 10, 13, 15–22 | none (backend in progress, spring-p14) | `CallsAuthzIT` + `CallsRelationIT` matrix (review R1, R5) |
| Call lifecycle (accept, decline, cancel, 45 s missed, busy, call_off, rate) | M-CAL-32–42 | none | Spring IT per state; the push matrix should add `call_incoming`, `call_state`, `call_missed`, `appt_call_ready` |
| Incoming call: app closed, locked, DND, silent, Doze | M-CAL-23–31 | none (only possible on real phones) | Android: `notify.sh` replay of `call_incoming` extras; stays manual for lock, DND and Doze |
| In-call media: network handover, poor network, BT, proximity, camera, mute | M-CAL-43–52 | `deploy/ci/call-smoke` (2 headless participants, ops-calls) | stays manual |
| Battery, heat, data use | M-CAL-54–56 | none | stays manual (record the numbers) |
| Web ↔ app | M-CAL-57–61 | none | Vue Playwright journey with a fake LiveKit; SSE `call_incoming` and `call_state` |
| Call history, privacy (no numbers, no recording, lock screen) | M-CAL-53, 62–65 | none | IT: no phone fields in any call payload; token grant unit test (R4) |
| Online chamber setup, booking, bKash claim/confirm | M-ONC-01–12 | none (spring-p15) | `OnlineChamberIT`: `providers` never has `bkashNumber`, pay transitions, duplicate TrxID (R6–R8) |
| Join window, waiting room, doctor-joined push, serial, prescription | M-ONC-13–23 | none | IT for the `appt_join` window; a web journey for doctor-on-web ↔ patient |
