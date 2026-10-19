# Phase 3 status (paused 2026-10-19)

Branch `spring-p7`; `spring` was fast-forwarded to `fa37e55` (= spring-p7 minus this file) just before the pause.

## Done
* **E2E workflow** `.github/workflows/backend-e2e.yml`: web journeys (`e2e/web`, 175 scenarios) against the Spring jar in
  the runner (Postgres/Redis services, fresh Flyway DB, Firebase Auth emulator via a throw-away service account,
  `PUSH_MODE=fake`, login codes read from Spring's log, machine clock 11:00 Dhaka) and against Node as baseline.
  Verdict = parity with Node. Results on log branch `ci-log-<branch>` (`spring` → `spring-ci-log`): `e2e-status.txt`,
  `e2e/summary.md`, `e2e/<spring|node>/`. Tooling: `e2e/server/spring-front.js`, `e2e/web/report.mjs`,
  `.github/scripts/publish-log.sh` (shared with backend.yml; each workflow replaces only its own files).
* **Deploy workflow** `.github/workflows/deploy-backend.yml` (dispatch only; push runs script checks) +
  `deploy/deploy-backend.sh`: jar upload, `/etc/lifolo/app.env`, systemd drop-in, Flyway on start, rollback to the
  previous jar, nginx `/api/ /r/ /rx/ /shop/ /.well-known/`, smoke checks, Bangla summary, log branch `ci-log-deploy`.
  Guide: `deploy/BASE-SETUP.md` (Bangla section "Deploy backend").
* **api.lifolo.com**: input `api_domain` (default `api.lifolo.com`); cert + own nginx site only when DNS points to the VPS.
* Backend change: `PUSH_MODE` (`fcm` | `log` | `fake`).

## Results
* Backend unit/integration tests: green (spring-p7 `ea93f00`; backend code unchanged after that).
* E2E run `a9304e1`: Spring = Node exactly — 119/175 scenarios, same 56 failures, same 57 bug steps, identical API
  4xx profile, 0 API 5xx, 0 Spring ERROR lines. Parity verdict on those reports: PASS. 0 Spring-side fixes needed.
* Deploy run 39284117747 (`31a3f91`, spring-p7): all checks ✅ incl. https://169-58-58-176.sslip.io/api/health and
  https://api.lifolo.com/api/health; Flyway 14 migrations (V601). Demo seed+wipe ⏭️ skipped on purpose:
  `demo-patient` already exists in Firebase Auth (Node/Vercel demo), wiping it would break that demo.

## Known breakage / not verified
* The 56 scenarios failing on both backends are journey scripts out of date with the current web UI (e.g. button
  "অ্যাপয়েন্টমেন্ট চাই" is now "এই চেম্বারে অ্যাপয়েন্টমেন্ট"). Not a Spring issue; they hide ~25% of the journeys from
  the parity check.
* The parity-gate commit (`fa37e55`) has not completed a CI run: its spring-p7 runs and the spring e2e run were
  cancelled to save budget; the `spring` backend.yml run was left running.
* Not yet live on the VPS: `fa37e55` (hides `/api/docs`, adds the check for it) — VPS runs `31a3f91`.
* Without SES secrets login-code emails are not sent (codes appear only in the VPS journal).
* Maestro/Android e2e not run against Spring (app BASE_URL is hard-coded; `app/` untouched).

## Next steps
1. Check `spring-ci-log` (`status.txt`) for `fa37e55`; then `gh workflow run backend-e2e.yml --ref spring` and confirm
   `e2e-status.txt` says `success` (parity PASS).
2. `gh workflow run deploy-backend.yml --ref spring` to put `fa37e55` on the VPS; read `ci-log-deploy`.
3. Update the 56 stale journey steps to the current web UI (separately, they fail on Node too), then re-run step 1.
4. Add SES secrets (`SES_REGION`, `SES_ACCESS_KEY_ID`, `SES_SECRET_ACCESS_KEY`, `SES_FROM`); optional R2/Sentry.
5. Cutover planning (app `ServerApi.kt` BASE_URL → https://api.lifolo.com, web build), keep Vercel for rollback.
