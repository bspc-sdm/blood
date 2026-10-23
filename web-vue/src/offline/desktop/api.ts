// সার্ভারের ডাক — অফলাইন ইঞ্জিনের জন্য। desktop_register সাধারণ ডাক; offline_bootstrap আর offline_sync-এ
// এই কম্পিউটারের গোপন চাবি (desktop_register-এর `secret`) দিয়ে HMAC সই (নিরাপত্তা পর্যালোচনা I5):
//   X-Lifolo-Device-Sig: t=<unix s>,v1=<hex HMAC-SHA256(secret, "<t>.<deviceId>.<body>")>
import { auth } from '@/lib/firebase';
import { call, ServerError } from '@/lib/api';
import { getLang } from '@/lib/i18n';
import { deviceSig } from '../engine/crypt';
import type { DeviceCred, EngineDeps } from '../engine/engine';

async function signed<T>(dev: DeviceCred, body: Record<string, unknown>): Promise<T> {
  const u = auth.currentUser;
  if (!u) throw new ServerError('login', 401, 'login_required');
  let token: string;
  try {
    token = await u.getIdToken(false);
  } catch {
    throw new ServerError('offline', 0);
  }
  const text = JSON.stringify({ lang: getLang(), ...body, deviceId: dev.deviceId });
  const sig = await deviceSig(dev.secret, dev.deviceId, text, Date.now());
  let res: Response;
  try {
    res = await fetch('/api/community', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8', Authorization: `Bearer ${token}`, 'X-Lifolo-Device-Sig': sig },
      body: text,
      credentials: 'omit',
    });
  } catch {
    throw new ServerError('offline', 0);
  }
  let json: any = {};
  try {
    json = await res.json();
  } catch {
    json = {};
  }
  if (!res.ok) throw new ServerError(json.error || `HTTP ${res.status}`, res.status, json.code);
  return json as T;
}

export const engineApi: EngineDeps['api'] = {
  register: (kind, bizId, name) => call('desktop_register', { kind, bizId, name }),
  bootstrap: (dev) => signed(dev, { action: 'offline_bootstrap' }),
  sync: (dev, ops, asOf) => signed(dev, { action: 'offline_sync', ops, ...(asOf ? { asOf } : {}) }),
};
