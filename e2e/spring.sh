#!/usr/bin/env bash
# e2e-র জন্য Spring (backend/-এর boot jar) চালু করা — CI (.github/workflows/backend-e2e.yml) আর লোকাল একই ভাবে:
#   PostgreSQL আর Redis আগে থেকে চালু; লগইন Firebase Auth emulator-এ (সামনের সার্ভার e2e/server/spring-front.js-এর
#   /__e2e/oauth-token নকল service account-এর token_uri); পুশ নকল (PUSH_MODE=fake); ইমেইল যায় না (কোড লগে)।
#
#   bash e2e/spring.sh <app.jar> <spring.log>
# env (না দিলে CI-র মান):
#   E2E_PG=jdbc:postgresql://127.0.0.1:5432/lifolo  E2E_PG_USER=lifolo  E2E_PG_PASSWORD=lifolo
#   E2E_REDIS=redis://127.0.0.1:6379  E2E_SPRING_PORT=8080  E2E_PORT=4600 (সামনের সার্ভার)  E2E_EMU_PORT=9099
# চালু হলে (/api/health সবুজ) ফেরে; Spring পেছনে চলতে থাকে (pid: <spring.log>.pid)।
set -euo pipefail
JAR="$1"
LOG="$2"
PORT="${E2E_SPRING_PORT:-8080}"
FRONT="${E2E_PORT:-4600}"
EMU="${E2E_EMU_PORT:-9099}"
work="$(mktemp -d)"

# নকল কিন্তু আসল ধাঁচের service account (Firebase Admin প্রোডাকশনের মতোই চালু হয়); token_uri = সামনের সার্ভার।
# এখান থেকে কিছুই Google-এ যায় না।
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/sa.pem" 2>/dev/null
SA="$(python3 - "$work/sa.pem" "$FRONT" <<'PY'
import json, sys
key = open(sys.argv[1]).read()
print(json.dumps({
    "type": "service_account", "project_id": "blood-3eb28", "private_key_id": "e2e",
    "private_key": key, "client_email": "e2e@blood-3eb28.iam.gserviceaccount.com", "client_id": "1",
    "token_uri": f"http://127.0.0.1:{sys.argv[2]}/__e2e/oauth-token",
}))
PY
)"
rm -rf "$work"

FIREBASE_SERVICE_ACCOUNT="$SA" \
SERVER_PORT="$PORT" \
SPRING_DATASOURCE_URL="${E2E_PG:-jdbc:postgresql://127.0.0.1:5432/lifolo}" \
SPRING_DATASOURCE_USERNAME="${E2E_PG_USER:-lifolo}" SPRING_DATASOURCE_PASSWORD="${E2E_PG_PASSWORD:-lifolo}" \
REDIS_URL="${E2E_REDIS:-redis://127.0.0.1:6379}" \
FIREBASE_AUTH_EMULATOR_HOST="127.0.0.1:$EMU" \
ADMIN_EMAILS=ekapkbd@gmail.com \
CRON_SECRET=e2e-cron-secret-0123456789 \
WEB_SESSION_SECRET=e2e-web-session-secret-0123456789 \
EMAIL_CODE_SECRET=e2e-email-code-secret-0123456789 \
PUSH_MODE=fake SENTRY_DSN='' FEATURES_ALL_ON=true \
  nohup java -Xmx1g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "$JAR" > "$LOG" 2>&1 &
echo $! > "$LOG.pid"
for i in $(seq 1 120); do
  if curl -fsS -m 3 "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
    echo "Spring up after ${i}x2s on :$PORT"
    curl -sS "http://127.0.0.1:$PORT/api/health"; echo
    exit 0
  fi
  if ! kill -0 "$(cat "$LOG.pid")" 2>/dev/null; then break; fi
  sleep 2
done
tail -80 "$LOG"
exit 1
