## ✅ blue/green deploy: বিরতি ছাড়া

commit `56687ab` (main) · load: requests 29109 in 131.2 s · 2xx health 17674 · 2xx API action 11435 · 5xx 0 · errors 0 · reused-connection retries 15 · ✅ 0 টা 5xx

| deploy | switch | 5xx (deploy-এর নিজের probe) | JobRunr | rollback | app |
|---|---|---|---|---|---|
| 1-legacy-to-bluegreen | bluegreen: 8080 → 8081 | requests=108 2xx=108 5xx=0 conn_errors=0 | running on :8081 (old has no hand-over endpoint (legacy) — new starts after the old one stopped) |  | up |
| 2-bluegreen | bluegreen: 8081 → 8080 | requests=108 2xx=108 5xx=0 conn_errors=0 | running on :8080 (old stopped at switch) |  | up |
| 3-broken-jar |  |  |  | ok (the previous release kept serving on :8080 — no switch, no downtime) | down |
| 4-after-failure | bluegreen: 8080 → 8081 | requests=108 2xx=108 5xx=0 conn_errors=0 | running on :8081 (old stopped at switch) |  | up |
