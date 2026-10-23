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

Where this section differs from the text above, this section wins. It folds in the "Web asks", the Android assumptions,
the security review (`docs/test-plan/CALLS-SECURITY-REVIEW.md`: R1–R5, R9, R11, R12) and the owner's 23 Oct decision
(every contact point: অডিও কল · ভিডিও কল · মেসেজ — see the messages contract, `backend/docs/CHAT.md`).

## Configuration
* Env: `LIVEKIT_URL` (the signalling URL returned to the apps as is — now `wss://lifolo.com` (LiveKit 1.13.10 on the main VPS, signalling at `/rtc`) —
  **never hard-coded**), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` (≥ 32 random bytes), optional `LIVEKIT_API_URL` (server
  API base for RoomService; default = `LIVEKIT_URL` with `wss→https`). If any of the first three is empty, calls are
  **unavailable**: `call_config` answers `{enabled:false}`, `contact_can` answers `canCall:false`, and
  `call_start` / `call_accept` / `call_upgrade(_accept)` / `appt_join` answer **503 `calls_unavailable`**
  ("অ্যাপে কল এখন চালু নেই — ফোনে কল করুন"). Everything else works without it.
* Call server (ops): `empty_timeout` 60 s, `departure_timeout` 20 s; nothing is recorded (no egress).
* **Tokens** (LiveKit access token, HS256 JWT): `iss` = API key, `sub` = identity = uid, `name`, `nbf`, `exp` = **10 min**
  (it only gates joining; get a fresh one from `call_status` / `appt_join`), `video` grant =
  `{room, roomJoin:true, canPublish:true, canSubscribe:true, canPublishSources, canPublishData:false, canUpdateOwnMetadata:false}`
  with `canPublishSources` = `["microphone"]` for an audio call, `["camera","microphone"]` for a video call. Never admin /
  create / list / record / ingress grants. Clients must never render remote video in an audio call.
* Every final state (declined / cancelled / missed / ended) deletes the room on the call server (`RoomService.DeleteRoom`),
  so an old token cannot get back in. Room name = `c_<callId>`; callId = 32 random alphanumerics (~190 bits).

## Feature switches (owner rule: ships OFF — FEATURES.md)
| key | gates |
|---|---|
| `calls_audio` | `call_start`, `call_accept`, `call_decline`, `call_cancel`; with `appt_video` as an alternative: `call_config`, `call_end`, `call_status`, `call_history`, `call_report`; with `chat` as an alternative: `call_block`, `call_unblock`, `call_blocks` |
| `calls_video` (needs `calls_audio`) | `call_start {video:true}`, answering with video, `call_upgrade(_accept/_decline)` |
| `appt_video` | `appt_join`, `appt_call_ready` |

* Off for the caller → **404 `feature_off`** "এই সুবিধা এখনো চালু হয়নি". Two-party: the other person must have it too —
  callee without `calls_audio` → 403 `call_not_allowed`; without `calls_video` the call rings / is answered as audio and an
  upgrade is refused (403); `appt_join` needs `appt_video` for the patient AND the doctor (else 403 `call_not_allowed`).
* `contact_can`: `canCall` = relation && call server && `calls_audio` on for BOTH; `canVideo` = canCall && `calls_video` on
  for both; `canMessage` = relation && `chat` on for both.
* Switching a key off (or taking someone out of the pilot) ends their live calls at the next 15 s check (`end_reason`
  `feature_off`); no `call_missed` push / bell entry for someone with calls off.
* Until the flags package (spring-p19) is on main, the backend uses a shim (`calls/CallFeatures.kt`) with the same
  `isOn / require / bothOn` calls: a key is on only when listed in `lifolo.feature-shim.keys` (empty in production = all
  OFF; tests `*`).

## Actions (`POST /api/community`, logged in; own account only — NOT via a caregiver's `asUid`)
| action | body | answer |
|---|---|---|
| `call_config` | — | `{ok, enabled, url?}` |
| `contact_can` | `{items:[{ctx, refId, peerUid?}]}` (≤ 50) | `{ok, items:[{ctx, refId, peerUid, canCall, canVideo, canMessage, blocked?}]}` |
| `call_start` | `{ctx, refId, peerUid?, video?}` | `{ok, callId, room, url, token, video, peer:{uid,name,photoThumb?}}` |
| `call_accept` | `{callId, video?}` (stored callee only) | `{ok, callId, room, url, token, video, peer}` — again from a 2nd device = same answer |
| `call_decline` | `{callId}` (stored callee only) | `{ok, state}` |
| `call_cancel` | `{callId}` (stored caller only; on an accepted call = end) | `{ok, state}` |
| `call_end` | `{callId}` (either side; a room: patient, doctor or assistant; on a ringing call: caller → cancelled, callee → declined) | `{ok, state}` |
| `call_status` | `{callId}` — **heartbeat: every 30 s while in a call** | `{ok, callId, state, ctx, refId, dir, video, peer, startedAt, answeredAt?, endedAt?, durationSec, url?, room?, token?, upgrade?:{by, at}}` — `url/room/token` (fresh, 10 min) whenever the call is `accepted` |
| `call_history` | `{before?}` (ms cursor = `startedAt` of the last item) | `{ok, items:[{callId, ctx, refId, dir:"in"\|"out", peer, video, state, startedAt, answeredAt?, durationSec}], next?}` (30 per page) |
| `call_upgrade` | `{callId}` (either side of an accepted audio call) | `{ok, video:false}` (`video:true` if it already is); the peer gets `call_upgrade {callId, by}` |
| `call_upgrade_accept` | `{callId}` (the other side) | `{ok, callId, url, token, video:true}`; the asker gets `call_upgraded {callId}` and a video token from `call_status` |
| `call_upgrade_decline` | `{callId}` | `{ok, video:false}`; the asker gets `call_upgrade_declined {callId}` |
| `call_block` | `{peerUid, callId?}` | `{ok}` — blocks calls **and messages** both ways; live calls between the two end at once |
| `call_unblock` | `{peerUid}` | `{ok}` |
| `call_blocks` | — | `{ok, items:[{uid, name, photoThumb?, blockedAt}]}` |
| `call_report` | `{callId, reason:"harassment"\|"spam"\|"fake"\|"other", note?}` (≤ 500 chars; a party of the call) | `{ok}` — stored with the call's metadata (`call_reports`) for the Lifolo team |
| `appt_join` | `{appointmentId}` (patient, doctor, or the doctor's current assistant) | `{ok, callId, room, url, token, video:true}` |

* `ctx`: `"blood" | "appointment" | "ambulance" | "rx_order" | "lab_order"` (the old `order` is gone — R5).
* `video` in `call_start` (optional): the caller picks audio or video in every context; default `true` for an
  online-chamber appointment, else `false`. `call_accept {video:false}` answers a video call with audio: then the call
  **is** audio (both tokens microphone-only) and video needs `call_upgrade`. `video:true` never turns an audio call into
  video. Missing `video` = as rung.
* Times are epoch ms. `startedAt` = placed, `answeredAt` = accepted, `durationSec` = talk time.
* States: `ringing → accepted | declined | cancelled | missed`; `accepted → ended`. Ringing ends after **45 s** → `missed`.
  The server also ends a live call when (checked every 15 s and on every `call_status`): the relation ended (donor
  removed, request closed, appointment no longer confirmed / past its window, order or trip finished), a block, an
  account deactivated / disabled / scheduled for deletion, or **heartbeats stopped** (no `call_status` from a side for
  90 s). An appointment room ends when nobody pinged for 90 s.
* `call_decline` / `call_cancel` / `call_end` on a finished call just answer its `state`. `call_accept` on a call that is
  no longer ringing → 409 `call_gone`. A caller's own older ringing call is cancelled when they start a new one.
* `contact_can`: the server's yes/no for the three buttons (অডিও কল · ভিডিও কল · মেসেজ) — the same rule as `call_start`
  and the messages (`ContactPolicy.decide`). `canCall`/`canVideo` also need the call server configured. It does NOT look
  at notification settings or busy (no presence leak); `call_start` still answers `call_off` / `call_busy`. `blocked:true`
  = you blocked this person (show "আনব্লক").

### Errors
| status / code | when |
|---|---|
| 400 `bad_request` | missing/invalid `ctx`, `refId`, `peerUid`, `video`, `reason`, `items` |
| 403 `call_not_allowed` | no relation (below), outside the time window, peer deactivated / deleting / disabled, demo ↔ real, calling yourself, **or the peer blocked you** (not revealed) |
| 403 `call_blocked` | **you** blocked this person ("আপনি এঁর সাথে যোগাযোগ বন্ধ রেখেছেন") — shared with messages |
| 404 | `callId` / appointment not found or not yours (same body for every case) |
| 409 `call_off` | callee turned "অ্যাপে কল" (`calls`) or "সব নোটিফিকেশন" off — "উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন" |
| 409 `call_busy` | callee has a ringing call, or an accepted call with live heartbeats |
| 409 `call_gone` | accepting / upgrading a call that is no longer ringing / accepted |
| 409 `no_upgrade` | `call_upgrade_accept/decline` with no pending request from the other side |
| 409 `appt_join_window` | `appt_join` earlier than 15 min before the slot or later than 3 h after |
| 409 `not_online` / `state` | `appt_join` on an in-person / not confirmed appointment |
| 409 `room_full` | `appt_join` by a second doctor-side person while the first is in the room (2 per room) |
| 429 `call_rate` | caller > 10 `call_start` / 10 min; **per pair** > 3 unanswered (declined / missed / cancelled) calls an hour; 2 declines in a row → 30 min pause for that pair; **per callee** > 20 rings an hour from everybody; > 30 `appt_join` / 10 min; > 5 `call_upgrade` per call / 10 min |
| 503 `calls_unavailable` | LiveKit env not set |

## Relations (`ContactPolicy` — the same for calls and messages)
* `blood` — `refId` = request id, request `open`. Requester → `peerUid` **required**: a donor whose response is
  `accepted` or `donated`. Donor → requester **only when the requester confirmed that donor, or after the requester
  contacted them first** about this request (a call or a message — `ContactHistory`).
* `appointment` — `refId` = appointment id, status `confirmed`, now within **slot − 30 min … slot + 3 h** (slot =
  visit date + `start`, Dhaka time; no start → the whole day). Patient → doctor (default) or `peerUid` = one of the
  doctor's assistants. Doctor or assistant → patient. Doctor suspended by the team → no.
* `ambulance` — `refId` = ambulance request id, request active (`open`/`accepted`/`arrived`). Patient → the driver who
  accepted (default) or `peerUid` = an owner with a live offer (open request). That driver/owner → patient.
* `rx_order` — `refId` = rx order id (`sent`/`ready`/`accepted`/`out`); patient ↔ pharmacy owner. `lab_order` —
  `refId` = lab order id (`sent`/`received`); patient ↔ lab owner. Listing suspended → no. Each looks only at its own table.
* Always: never demo ↔ real, never to/from a deactivated, deleting (`delete_at`) or disabled (admin sanction) account,
  never across a block (either way).

## Reusable services (Kotlin, for the messages backend — package `com.lifolo.contact`)
```kotlin
// relation + donor rule + demo/real + deactivated/deleting/disabled + blocks
class ContactPolicy {
    enum class Refusal { NO_RELATION, BLOCKED_BY_ME, BLOCKED_BY_PEER, UNREACHABLE }
    data class Rel(val peer: String, val defaultVideo: Boolean)
    data class Decision(val rel: Rel?, val refusal: Refusal?) { val allowed: Boolean }
    fun decide(uid: String, uidEmail: String, ctx: String, refId: String, peerUid: String?, nowMs: Long = now): Decision
    fun canContact(ctx: String, refId: String, a: String, b: String, aEmail: String = accountEmail(a), nowMs: Long = now): Boolean
    fun check(caller: String, callerEmail: String, ctx: String, refId: String, peerUid: String?, nowMs: Long): Rel?  // relation only
    fun reachable(uid: String): Boolean
    fun notAllowed(lang: String?): ApiException          // 403 call_not_allowed (messages use their own code)
}
// uidEmail = the caller's VERIFIED email, lower case, or "" (assistants are matched by it): ctx.user.emailVerified ? email : ""
// map refusals: BLOCKED_BY_ME → 403 call_blocked; everything else → 403 *_not_allowed (a block by the peer is not revealed)

fun interface ContactHistory { fun contacted(from: String, to: String, ctx: String, refId: String): Boolean }
// register a @Component (messages: "from sent a message to to in this thread") — the donor rule asks every bean

class BlockService {
    fun isBlocked(blocker: String, blocked: String): Boolean
    fun eitherWay(a: String, b: String): Boolean
    fun block(blocker: String, blocked: String, atMs: Long = now): Boolean
    fun unblock(blocker: String, blocked: String): Boolean
    fun list(blocker: String, limit: Int = 200): List<Pair<String, Long>>   // (uid, blockedAt)
}
// CallService.blockedByMe(lang) gives the shared 403 call_blocked error; call_block also ends live calls (revokeBetween).
```

## Push (FCM data-only, high priority; every value is a string) — lock screen never shows context (R9)
| type | cat | to | data | inbox |
|---|---|---|---|---|
| `call_incoming` | `calls` | callee | `callId, video:"true"/"false", caller` (**first name only**)`, callerUid`; title "লাইফলো কল", body = first name; **TTL 45 s**. No ctx/refId — fetch with `call_status` after unlock | no |
| `call_state` | `calls` | both sides, every change out of ringing and every end | `callId, state`; TTL 60 s (stops ringing on all devices) | no |
| `call_missed` | `calls` | callee, on the 45 s timeout | `callId, video, caller` (first name)`, callerUid, missedCount`; title "লাইফলো: মিসড কল" / "লাইফলো: ৩টা মিসড কল", body "<first name> কল করেছিলেন"; FCM collapse key `missed_<callerUid>`; the bell keeps one entry per caller | yes (1 per caller) |
| `call_upgrade` | `calls` | the other side | `callId, by` | no |
| `call_upgraded` / `call_upgrade_declined` | `calls` | the asker | `callId` | no |
| `appt_call_ready` | `appointments` | patient, when the doctor/assistant joins (≤ once per 2 min) | `appointmentId, callId`; title "লাইফলো: ভিডিও কলে যুক্ত হোন" | yes |

No phone numbers in any call payload. Android: `VISIBILITY_PRIVATE` with a generic public version for `call_missed`
and `appt_call_ready`.

## SSE (`GET /api/events`, topic `user:{uid}`) — in-page only, so it carries the details
* `call_incoming` `{callId, video, ctx, refId, caller:{uid, name, photoThumb?}}` → callee
* `call_state` `{callId, state}` → both sides on every change
* `call_upgrade` `{callId, by}` → the other side; `call_upgraded` / `call_upgrade_declined` `{callId}` → the asker
* `appt_call_ready` `{appointmentId, callId}` → patient

## Notification category
`calls` is a settable category (last in the list; default on): `notify_prefs_get` → `{ok, all, cats:{blood_nearby,
blood_emergency, my_request, appointments, rx, ambulance, community, business, calls}}`; `notify_prefs_set {cats:{calls:false}}`.

## Online chambers
The online-chamber API (chamber `mode` / `bkashNumber` / `note`, appointment `online` / `payState` / `payRef`, the bKash
fee actions, my_appointments / rx_patients fields) is specified in **[CHAMBERS-ONLINE.md](CHAMBERS-ONLINE.md)** (spring-p15).
The calls side of it:
* `appt_join`: online + confirmed, **slot − 15 min … slot + 3 h**; at most one live room per appointment (joins share it);
  after it ended (`call_end`, `room_finished`, 90 s without heartbeats, or the appointment changed) the next join opens a
  **new** room (new callId) — old tokens never lead back in. Joining is never gated on `payState`.
* The call server allows **2 per room**: the patient and ONE person of the doctor's side. While the doctor (or an
  assistant) is in the room and pinging, another doctor-side join gets **409 `room_full`** ("ডাক্তারের পক্ষ থেকে একজন আগেই
  এই ভিডিও কলে আছেন — একসাথে দুজন যুক্ত হওয়া যায় না"); the seat frees when that person leaves (webhook) or goes quiet 90 s.
* The first doctor/assistant join (and again after 2 min) pushes `appt_call_ready` to the patient, and sets
  `appointments.doctor_joined_at` (first join only; when that column exists — V621).

## Call server webhook
`POST /api/livekit/webhook` (from LiveKit, not the apps): verified like LiveKit's WebhookReceiver — `Authorization` =
HS256 JWT signed with `LIVEKIT_API_SECRET`, `iss` = `LIVEKIT_API_KEY`, `sha256` claim = base64(SHA-256(body)); else 401.
`room_finished` → that room's call ends (`end_reason` `room_finished`); `participant_left` → an answered 1:1 call ends
(`left`), a ringing call whose caller left is cancelled, an appointment room frees the doctor-side seat (the room stays).
LiveKit config (deploy/setup-call-server.sh): `webhook: {api_key: <key>, urls: [http://127.0.0.1:8080/api/livekit/webhook]}`
on the main VPS (`LIFOLO_WEBHOOK_URL` overrides; dedicated mode: `https://lifolo.com/api/livekit/webhook`).

## Privacy and retention
Calls are never recorded (no recording/egress grants; the call server only relays live audio/video, over encrypted
connections, TURN relay when needed). The server keeps only metadata: who called whom, context, when, video or not,
state, duration, why it ended. Rows are deleted **180 days** after the call (daily job step `callsDeleted`) and at once
when either person's account is deleted (with their blocks and the reports they made). Call reports are kept until
handled, then one year. Call-server logs: 14 days (ops).
