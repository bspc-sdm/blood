# Lifolo in-app calls + online (video) appointments — shared CONTRACT (owner-approved, 23 Oct 2026)

Owner decisions: calls only between people who already have a relation (below); normal phone call buttons stay (primary in emergencies: ambulance, emergency blood); NO recording; online consultation fee paid via the doctor's own bKash number (no in-app payment); LiveKit self-hosted on a separate small VPS at wss://call.lifolo.com (DNS-only, not Cloudflare-proxied).

## Relations (server enforces; otherwise 403 `call_not_allowed`)
- `blood`: requester <-> a donor who responded "রক্ত দেব" to that request, while the request is open.
- `appointment`: patient <-> doctor (and the doctor's assistants) for that appointment, status confirmed/serial, from 30 min before the slot until 3 h after; online-chamber appointments default to video.
- `ambulance`: patient <-> the driver who offered/accepted on that active ambulance request.
- `order`: patient <-> the pharmacy/lab owner for an active prescription order / lab order.
Never between deactivated accounts, demo<->real accounts, or blocked/sanctioned users.

## Actions (/api/community)
- `call_start {ctx:"blood"|"appointment"|"ambulance"|"order", refId:string, peerUid?:string, video:boolean}` -> `{ok, callId, room, url:"wss://call.lifolo.com", token, peer:{uid,name,photoThumb?}}`. Server sends the callee an FCM data push `type:"call_incoming"`, `cat:"calls"`, `callId`, `video`, `caller` name, high priority, TTL 45 s. Rate limit: 10 starts / 10 min per user.
- `call_accept {callId}` -> `{ok, room, url, token}`; `call_decline {callId}`; `call_cancel {callId}` (caller hangs up before answer); `call_end {callId}`.
- `call_status {callId}` -> `{state:"ringing"|"accepted"|"declined"|"missed"|"ended"|"cancelled", startedAt?, endedAt?}`.
- Ringing times out after 45 s -> `missed`; callee gets push `type:"call_missed"` (cat `calls`) + inbox entry; caller sees state missed.
- `call_history {before?}` -> last calls (both directions): callId, ctx, refId, peer, video, state, startedAt, durationSec.
- Busy: callee already in an active call -> 409 `call_busy`.
- Tokens: LiveKit JWT, TTL 2 h, identity = uid, room `c_<callId>`, canPublish/canSubscribe only, no recording/egress.

## Notification category
- New category `calls` ("অ্যাপে কল", default on) added to notify prefs (notify_prefs_get/set). If off: call_start returns 409 `call_off` with message "উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন" and the caller UI offers the phone number if the context allows it. The master switch "সব নোটিফিকেশন" off also blocks ringing (same 409).

## Online chambers (doctor)
- Chamber gets `mode: "in_person" | "online"`. Online chamber: days/times/serial capacity/fee like now, plus `bkashNumber` (shown to patients after booking) and optional note. No address.
- Appointment in an online chamber has `payState: "unpaid" | "claimed" | "paid"` and optional `payRef` (bKash TrxID): patient taps "টাকা পাঠিয়েছি" (+TrxID) -> claimed; doctor/assistant taps "ফি পেয়েছি" -> paid; doctor can still join unpaid (his choice).
- `appt_join {appointmentId}` -> `{ok, callId, room, url, token}` for patient or doctor/assistant, allowed from 15 min before slot to 3 h after; creates/reuses one call per appointment; the doctor joining pushes the patient `type:"appt_call_ready"` (cat `appointments`) "ডাক্তার ভিডিও কলে আছেন — যুক্ত হোন". Queue/serial logic unchanged ("আপনার আগে ২ জন" still works).
- After the call the doctor writes the prescription as today (existing rx flow, patient account linked).

## Web asks (web-vue agent, 23 Oct) — backend please confirm/adjust in backend/docs/CALLS.md
Web will build against these unless CALLS.md says otherwise:
1. SSE on GET /api/events (topic user:{uid}, Bearer via fetch stream): event `call_incoming` data `{callId, video, ctx, refId, caller:{uid,name,photoThumb?}}`; event `call_state` data `{callId, state}` to BOTH parties on every change (accepted/declined/missed/ended/cancelled) so the caller's ringing view and the callee's banner close without polling; event `appt_call_ready` data `{appointmentId}`. Web also polls `call_status` every 3 s while ringing/in-call as a fallback.
2. `call_history` entries carry `dir: "in"|"out"` (otherwise web cannot label incoming/outgoing). Web fallback when SSE is down: poll `call_history` every 15 s and treat `dir:"in", state:"ringing"` as an incoming call.
3. Feature flag: `call_config` (logged-in) -> `{enabled:boolean}`; if the action is unknown or enabled=false, web hides every in-app call button.
4. Online chamber fields on chambers: `mode`, `bkashNumber`, `note`; appointment: `payState`, `payRef`, `online:boolean` (or chamber.mode). Actions web will call: `appt_pay_claim {appointmentId, payRef?}` (patient, -> claimed) and `appt_pay_confirm {appointmentId}` (doctor/assistant, -> paid). Please name these in CALLS.md if different.

---

# Backend — final API (what the server implements; app and web build against THIS section)

Where this section differs from the text above, this section wins. Answers to "Web asks" and the Android assumptions are
folded in (all adopted).

## Configuration
* Env: `LIVEKIT_URL` (the signalling URL returned to the apps, e.g. `wss://lifolo.com/<path>` while testing — **never
  hard-coded**), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`. If any is empty, calls are **unavailable**: `call_config`
  answers `{enabled:false}` and `call_start` / `call_accept` / `appt_join` answer **503 `calls_unavailable`**
  ("অ্যাপে কল এখন চালু নেই — ফোনে কল করুন"). Everything else (status, history, end, online-chamber booking and
  payment) works without it.
* Tokens: LiveKit access token, HS256 JWT signed with the API secret. Claims: `iss` = API key, `sub` = identity = uid,
  `name` = display name, `nbf` = now, `exp` = now + 2 h, `video` = `{room:"c_<callId>", roomJoin:true, canPublish:true,
  canSubscribe:true}` — no admin / create / record / egress / ingress grants. Nothing is recorded.

## Actions (`POST /api/community`, logged in; NOT available to a caregiver via `asUid`)
| action | body | answer |
|---|---|---|
| `call_config` | — | `{ok, enabled, url?}` (`url` = LIVEKIT_URL when enabled) |
| `call_start` | `{ctx:"blood"\|"appointment"\|"ambulance"\|"order", refId, peerUid?, video?}` | `{ok, callId, room, url, token, video, peer:{uid,name,photoThumb?}}` |
| `call_accept` | `{callId}` (callee) | `{ok, callId, room, url, token, video, peer}` — again on a 2nd device = same answer |
| `call_decline` | `{callId}` (callee) | `{ok, state}` |
| `call_cancel` | `{callId}` (caller, before answer; on an accepted call = end) | `{ok, state}` |
| `call_end` | `{callId}` (either side; on a ringing call: caller → cancelled, callee → declined) | `{ok, state}` |
| `call_status` | `{callId}` (either side; assistants for an appointment room) | `{ok, callId, state, ctx, refId, dir, video, peer, startedAt, answeredAt?, endedAt?, durationSec}` |
| `call_history` | `{before?}` (ms cursor = `startedAt` of the last item) | `{ok, items:[{callId, ctx, refId, dir:"in"\|"out", peer:{uid,name,photoThumb?}, video, state, startedAt, answeredAt?, durationSec}], next?}` (30 per page, newest first) |
| `appt_join` | `{appointmentId}` (patient, doctor, or the doctor's assistant) | `{ok, callId, room, url, token, video:true}` |
| `appt_pay_claim` | `{appointmentId, payRef?}` (patient — "টাকা পাঠিয়েছি", optional bKash TrxID ≤ 30 chars) | `{ok, payState:"claimed", payRef}` — caregiver (`asUid`) allowed |
| `appt_pay_confirm` | `{appointmentId}` (doctor or assistant — "ফি পেয়েছি") | `{ok, payState:"paid"}` |

* `video` in `call_start` is optional: default `true` for an online-chamber appointment, else `false`.
* Times are epoch ms. `startedAt` = when the call was placed, `answeredAt` = accepted, `durationSec` = talk time
  (`endedAt − answeredAt`, 0 if never answered).
* States: `ringing → accepted | declined | cancelled | missed`; `accepted → ended`. Ringing ends after **45 s** →
  `missed` (a JobRunr job every 15 s + checked on every read). An accepted call nobody ended is closed as `ended` 2 h
  after it was answered (token lifetime).
* Calling `call_decline` / `call_cancel` / `call_end` on a finished call just answers its `state` (idempotent).
  `call_accept` on a call that is no longer ringing → **409 `call_gone`**.
* A caller's own older ringing call is cancelled when they start a new one.

### Errors
| status / code | when |
|---|---|
| 400 `bad_request` | missing/invalid `ctx`, `refId`, `peerUid`, `callId` |
| 403 `call_not_allowed` | no relation (below), outside the time window, peer deactivated / deleting / disabled, demo ↔ real, calling yourself |
| 404 | `callId` / appointment not found or not yours |
| 409 `call_off` | callee turned "অ্যাপে কল" (`calls`) or "সব নোটিফিকেশন" off — "উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন" |
| 409 `call_busy` | callee has a ringing call or an accepted call (answered < 2 h ago, not ended) — "উনি এখন অন্য কলে আছেন" |
| 409 `call_gone` | accepting a call that is no longer ringing |
| 409 `appt_join_window` | `appt_join` earlier than 15 min before the slot or later than 3 h after |
| 409 `not_online` | `appt_join` / pay actions on an in-person appointment |
| 409 `state` | `appt_join` / pay actions when the appointment is not confirmed (pay claim: pending also allowed) |
| 429 `call_rate` | more than 10 `call_start` in 10 min (also 30 `appt_join` / 10 min) |
| 503 `calls_unavailable` | LiveKit env not set |

## Relations (`ctx`, `refId`, who is the peer)
* `blood` — `refId` = request id, request `open`. Requester → `peerUid` **required**: a donor whose response is
  `accepted` or `donated`. Donor (accepted/donated) → peer = requester.
* `appointment` — `refId` = appointment id, status `confirmed`, now within **slot − 30 min … slot + 3 h** (slot =
  visit date + `start` in Dhaka time; no start → the whole day). Patient → doctor (default) or `peerUid` = one of the
  doctor's assistants (uid of an account whose verified email is on the doctor's list). Doctor or assistant → patient.
  Doctor suspended by the team → not allowed.
* `ambulance` — `refId` = ambulance request id, request active (`open`/`accepted`/`arrived`). Patient → the driver who
  accepted (default) or `peerUid` = an owner who made an offer (open request). That driver/owner → patient.
* `order` — `refId` = an rx order id (`sent`/`ready`/`accepted`/`out`) or a lab order id (`sent`/`received`). Patient ↔
  pharmacy owner / lab owner. Listing suspended → not allowed.
* Always: no demo ↔ real, never to/from a deactivated, deleting (`delete_at`) or disabled account.

## Push (FCM data-only, high priority; every value is a string)
| type | cat | to | data | inbox |
|---|---|---|---|---|
| `call_incoming` | `calls` | callee | `callId, video:"true"/"false", caller (name), callerUid, ctx, refId` + title/body; **TTL 45 s** | no |
| `call_state` | `calls` | both sides, on every change out of ringing and on end | `callId, state`; TTL 60 s (stops ringing on all devices) | no |
| `call_missed` | `calls` | callee, on the 45 s timeout | `callId, video, caller, callerUid, ctx, refId` + title "মিসড কল" | yes |
| `appt_call_ready` | `appointments` | patient, when the doctor/assistant joins (at most once per 2 min) | `appointmentId, callId` + "ডাক্তার ভিডিও কলে আছেন — যুক্ত হোন" | yes |

No phone numbers in any call payload. `callerPhoto` is NOT in the push (FCM 4 KB limit) — use `call_status` →
`peer.photoThumb`.

## SSE (`GET /api/events`, topic `user:{uid}`)
* `call_incoming` `{callId, video, ctx, refId, caller:{uid, name, photoThumb?}}` → callee
* `call_state` `{callId, state}` → both sides on every change
* `appt_call_ready` `{appointmentId, callId}` → patient

## Notification category
`calls` is a new settable category (last in the list; default on): `notify_prefs_get` → `{ok, all, cats:{blood_nearby,
blood_emergency, my_request, appointments, rx, ambulance, community, business, calls}}`; `notify_prefs_set {cats:{calls:false}}`.

## Online chambers
> **Owned by the spring-p15 backend agent** (chamber mode/bkashNumber/note, appointment `online`/`payState`/`payRef`,
> `appt_pay_claim` / `appt_pay_confirm`, my_appointments / rx_patients fields). The text below is the agreed shape; spring-p15
> may refine it here. spring-p14 (calls) implements only `appt_join` + `appt_call_ready`, reading `appointments.online`.

* Chamber JSON (`provider_save` in, `provider_me` / `providers` out): `mode: "in_person" | "online"` (missing =
  `in_person`). An online chamber: `{mode:"online", days/start/end or times[], fee/noFee, appointments, patientMin?,
  bkashNumber, note?}` — no `hospitalId`, no address, at most **one** online chamber, doctors only.
  `bkashNumber` (Bangladesh mobile, 01XXXXXXXXX) is required unless `noFee`; `note` ≤ 200 chars. Out:
  `hospitalId:null`, `hospitalName:"Online video consultation"`, `hospitalNameBn:"অনলাইন ভিডিও পরামর্শ"`, `mode`, `note`;
  `bkashNumber` only in `provider_me` (the public `providers` list never shows it).
* `book` on an online chamber: no `chamberKey` needed (its key is `online`). Answer stays `{id}`.
* `my_appointments` items (both roles) add `online:boolean`; online ones also `payState` ("unpaid"|"claimed"|"paid"),
  `payRef`, `fee`, `noFee`, `onlineNote`, and `bkashNumber` (patient side only). Values are copied at booking.
* `rx_patients.appointments[]` items add `online`, `payState`, `payRef`, `start`.
* Pay flow: `appt_pay_claim` (unpaid/claimed → claimed, pushes the doctor `type:"appointment", role:"provider"`),
  `appt_pay_confirm` (→ paid, pushes the patient `type:"appointment", role:"patient"`). The doctor may join unpaid.
* `appt_join`: online + confirmed, **slot − 15 min … slot + 3 h**; one call (room) per appointment, re-used and re-opened
  on every join; the first doctor/assistant join (and again after 2 min) pushes `appt_call_ready` to the patient. The
  doctor ending the room (`call_end`) → state `ended`; joining again re-opens it.

## Privacy and retention
Calls are never recorded (no recording/egress grants). The server keeps only metadata: who called whom, context, when,
video or not, state, duration. Rows are deleted **180 days** after the call (daily job step `callsDeleted`) and at once
when either person's account is deleted.
