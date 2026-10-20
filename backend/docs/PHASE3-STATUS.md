# Phase 3 status

Branch `spring-p7` → fast-forwarded into `spring` when green.

## Done
* **E2E workflow** `.github/workflows/backend-e2e.yml`: the web journeys (`e2e/web`, 9 journeys, 175 scenarios, ~440
  steps) against the Spring jar in the runner (Postgres/Redis services, fresh Flyway DB, Firebase Auth emulator via a
  throw-away service account, `PUSH_MODE=fake`, login codes read from Spring's log, machine clock 11:00 Dhaka) and
  against Node as the baseline. Verdict = parity: Spring fails nothing Node passes, same API 4xx profile, no 5xx, no
  Spring ERROR. Results on `ci-log-<branch>` (`spring` → `spring-ci-log`): `e2e-status.txt`, `e2e/summary.md`,
  `e2e/<spring|node>/` (reports, failed-step screenshots, logs). Tooling: `e2e/server/spring-front.js`,
  `e2e/web/report.mjs`, `.github/scripts/publish-log.sh` (shared with backend.yml).
* **Journeys updated to the current web UI** (they were written before the 2.3x web changes): booking from the chamber
  cards, specialty chips, pharmacy/lab work pages as tabs, orders moving between tabs, the pharmacy list's
  "shop or online" choice, no copy button on details, the ambulance CallBar, the fare-note field, the seeded hospital
  request. Only how the journeys reach a screen changed — the expected texts and numbers are the same. Helpers in
  `e2e/web/lib.mjs` (`bookBtn`, `pharmacyWork`, `labWorkTab`, `orderRow`, `inStore`, `copyIfThere`). Node stack
  locally: 175/175 scenarios, 0 bug steps.
* **Deploy workflow** `.github/workflows/deploy-backend.yml` (dispatch only) + `deploy/deploy-backend.sh`; Bangla guide
  in `deploy/BASE-SETUP.md`. Live: https://169-58-58-176.sslip.io/api/health and https://api.lifolo.com/api/health
  (input `api_domain`; cert only when DNS points to the VPS). `/api/docs`, `/actuator`, JobRunr dashboard not public.
* **Email (SES)**: secrets `SES_REGION` (ap-southeast-2), `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`, `SES_FROM`
  (no-reply@lifolo.com) go into `/etc/lifolo/app.env`. Checks without sending mail: the runner calls SES
  `GetAccount` (AccessDenied with the SendEmail-only IAM user still proves the key and signature), and the app builds
  the SES client at start and logs `Mail: Amazon SES client ready` (the deploy reads it from the journal).
* Backend changes in phase 3: `PUSH_MODE` (`fcm` | `log` | `fake`), SES client built at start-up (+ log line).

## Results
* Backend tests: 345/345 green (`fa37e55`; later backend change = SES start-up log only, checked by CI).
* E2E `fa37e55` (old journeys): Spring = Node exactly (119/175, same 56 stale-journey failures) → parity PASS.
* E2E `484468a` (updated journeys): Node 175/175; Spring 174/175 — S-28 (admin approval checklist ticked before it had
  loaded: a race in the journey, the screenshot shows the same checklist). Fixed in the journey (`tickAll` waits).
  Re-run `f3d6adc`: **Spring 175/175, Node 175/175**, 0 bug steps, identical API 4xx profile, 0 5xx, 0 Spring ERROR →
  parity PASS. Spring-side code fixes needed by the suites: none.
* Deploy `fa37e55` from `spring`: all checks ✅ (both hosts, Flyway V601, nginx routes, hidden docs/actuator/JobRunr).
  Demo seed+wipe ⏭️ on purpose: `demo-patient` exists in Firebase Auth (the Vercel demo), wiping it would break that demo.

## Known gaps
* SES is probably still in the sandbox: login-code emails reach only verified addresses until AWS approves production
  access.
* Maestro/Android e2e not run against Spring (app BASE_URL hard-coded; `app/` untouched).
* `deploy/` was copied from `main` and changed on `spring` (deploy-managed nginx snippet) — expect a merge conflict
  only if `main` changes those files first.

## Next steps
1. Cutover planning: app `ServerApi.kt` BASE_URL and the web build → https://api.lifolo.com; Vercel kept for rollback.
2. After SES production access: one real login with a non-verified address.
3. Optional: run the Android Maestro flows against a debug build pointing at the VPS.
