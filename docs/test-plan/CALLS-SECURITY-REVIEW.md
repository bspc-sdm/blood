# In-app calls + online chambers: security and abuse review

Review of the owner-approved calls CONTRACT (23 Oct 2026) and the backend's final API in `backend/docs/CALLS.md`
(branch `spring-p14`, "Backend — final API" section). This is a docs-only review. No code was changed, and nothing
here has been proven by a test yet. Each item gives a concrete fix and the test that should prove it. Manual tests
that exercise these items are in [manual/17-calls.md](manual/17-calls.md).

**সংক্ষেপে (মালিকের জন্য):** ১৪টা বিষয়: 🟠 High ৬টা, 🟡 Medium ৬টা, 🟢 Low ২টা। কোনোটাই Critical নয়, তবে প্রোডাকশনে ডেমো চালু থাকলে R6 Critical
(পুরোনো S1-এর সাথে যুক্ত)। সবচেয়ে বড় ঝুঁকিগুলো: (১) "রক্ত দেব" চাপলেই যে কেউ রক্তপ্রার্থীকে ভিডিও কল করতে পারেন, আর কাউকে আটকানোর (block) উপায় নেই;
(২) একজনকে বারবার কল করে বিরক্ত করার সীমা শুধু কলকারী ধরে, যিনি কল পান তাঁকে ধরে নয়; (৩) সম্পর্ক শেষ হলে বা অ্যাকাউন্ট বন্ধ হলেও কলের চাবি (token) ২ ঘণ্টা
চলে; (৪) অডিও কলেও ক্যামেরা আর স্ক্রিন শেয়ার খোলা থাকে; (৫) ডেমো ডাক্তারের অনলাইন চেম্বারের "নোট" সবাই দেখেন, সেখানে যে কেউ bKash নম্বর লিখে টাকা চাইতে পারেন।

Severity scale: as in [06-phases-and-exit-criteria.md](06-phases-and-exit-criteria.md#তীব্রতা-severity). 🔴 Critical,
🟠 High, 🟡 Medium, 🟢 Low. High items block the calls feature flag (`call_config.enabled`) from being turned on in
production.

## Summary

| # | Topic | Severity |
|---|---|---|
| R1 | Harassment: a donor gets call rights just by tapping "রক্ত দেব"; there is no per-person block | 🟠 High |
| R2 | Call bombing: the rate limit counts per caller only, never per callee or per pair | 🟠 High |
| R3 | Token TTL 2 h, never revoked: removed relations and deactivated or disabled users keep room access | 🟠 High |
| R4 | Token grants too broad: an "audio" call can publish camera, screen share and data | 🟠 High |
| R5 | IDOR: `refId`/`peerUid`/`callId` binding must be proven per context; the `order` refId namespace is ambiguous | 🟠 High |
| R6 | Online-chamber `note` is public free text: a demo or compromised doctor can post a bKash number to every patient | 🟠 High (🔴 while demo is live in production) |
| R7 | Fake bKash TrxID claims | 🟡 Medium |
| R8 | A doctor takes the fee and then denies service (cancel, never join); there is no dispute trail | 🟡 Medium |
| R9 | Lock-screen push leaks the caller's name and health context | 🟡 Medium |
| R10 | Minors: no age signal; strangers can video-call | 🟡 Medium |
| R11 | Stale "busy" and ghost rooms: no server-side detection that a call ended | 🟡 Medium |
| R12 | Data retention and privacy policy: call metadata, LiveKit logs, an exposed VPS | 🟡 Medium |
| R13 | Room-name or `callId` guessing | 🟢 Low |
| R14 | Presence leak via `call_busy` / `call_off` | 🟢 Low |

---

## R1: Harassment via the blood context, and no block (High)

**What the contract allows.** The `blood` relation is "a donor whose response is `accepted` or `donated`".
In the backend, `accepted` is the status written the moment anyone taps "রক্ত দেব". The requester does not have
to confirm anything first (`DonorService.kt`: "A response stays 'accepted' when confirmed"). As a result:
* Any logged-in user in the area can respond to any open request and immediately ring, or video-call, the
  requester. The requester is often a distressed family member, sometimes a woman sharing her own number.
  This lasts as long as the request is open, which can be days.
* The CONTRACT promises "never … blocked/sanctioned users", but **Lifolo has no user-to-user block feature**.
  `grep` finds no block table or action outside home-visit. The only defence is "ডোনার বাদ" (remove donor), and a
  new account can respond again.
* The same pattern applies to `ambulance`. An owner who merely *offered* can call the patient while the request is
  open. That is lower risk, because offering drivers are verified listings.

**Fix.**
1. In `blood`, a donor may call the requester **only after the requester has confirmed them** (`confirmed = true`)
   **or after the requester has called that donor once**. The requester may call any accepted donor.
2. Blood, ambulance and order calls are **audio-only by default**. Video is allowed there only if the callee turns
   it on after answering. Video stays the default only for `appointment`.
3. Add `call_block {peerUid}` / `call_unblock`, reachable from the call screen, the call history and the
   incoming-call notification ("এই মানুষের অ্যাপে কল বন্ধ করুন"). `call_start` then returns 403 `call_not_allowed`
   with the same generic message, so the block is not revealed. Add "রিপোর্ট" next to it, which creates a
   moderation report with the call metadata attached.
4. Removing a donor, closing a request, ending a trip or finishing an order ends any ringing or accepted call in
   that context at once (see R3).

**Test.** Add `CallsRelationIT`: a fresh account responds and then calls the requester, which should give 403
until the requester confirms. Add a block → 403 case. Manual: M-CAL-06, M-CAL-14, M-CAL-16.

## R2: Call bombing and rate limits (High)

**What the contract allows.** The limit is `10 call_start / 10 min per user`. That is per caller only:
* One caller can ring the same victim 10 times in 10 minutes, which is 60 rings an hour. Each ring lasts 45 s,
  so the victim is "ringing" (and therefore `call_busy` to everyone else) about 75% of the time.
* N accounts multiply this. 03a S7 already notes that new accounts are cheap: about 120 new emails a day can sign
  up.
* Each missed ring also produces a `call_missed` push and an inbox entry, so the inbox floods too.

**Fix.**
1. **Per pair:** at most 3 unanswered calls (declined, missed or cancelled) from A to B per hour. After 2
   consecutive declines, A cannot call B for 30 minutes. Both return 429 `call_rate`.
2. **Per callee:** at most 20 incoming rings per hour from all callers combined. Rings beyond that go silently
   to `missed`, without pushing again.
3. **New accounts:** an account less than 24 h old, or without a verified email, cannot start calls. A demo
   account counts as verified only inside the demo world.
4. Collapse `call_missed` pushes per caller: one notification, updated in place, saying "৩টা মিসড কল".
5. `appt_join` already caps the patient push at once per 2 min. Keep that.

**Test.** `CallsRateIT`: 4th unanswered call to the same peer → 429; 21st ring to one callee from 21 callers →
no push. Manual: M-CAL-40.

## R3: Token lifetime and revocation (High)

**What the contract allows.** The LiveKit JWT lives 2 h (`exp = now + 2 h`). Nothing in the contract removes a
participant or deletes a room when the server-side state changes. So for up to 2 h, a token holder can still
connect or reconnect to `c_<callId>` after any of these:
* the call was declined, cancelled or ended;
* the relation ended: donor removed, request closed, appointment cancelled, order delivered, or an assistant
  removed from the doctor's list;
* the account was deactivated ("সাময়িক বন্ধ"), disabled by an admin, or scheduled for deletion.

The appointment room is reused for the whole slot window ("one call per appointment, re-used and re-opened on
every join"). A stale token therefore lets someone walk back into a later session of the same appointment.

**Fix.**
1. Set the token TTL to **10 minutes**. The TTL only gates joining. LiveKit refreshes tokens for participants who
   are already connected, so long calls still work.
2. On every terminal transition (`declined`, `cancelled`, `missed`, `ended`), the server calls LiveKit
   `RoomService.DeleteRoom("c_<callId>")`. For the reused appointment room, it calls `RemoveParticipant` for the
   identity that lost its right.
3. Hook account deactivation, admin disable, delete scheduling, donor removal, request close/expiry, appointment
   cancel, ambulance cancel/finish and order finish into one place, `CallService.revokeFor(uid | ctx+refId)`. That
   function ends the calls and removes the participants.
4. Configure LiveKit with `empty_timeout` of 60 s and `departure_timeout` of 20 s, so abandoned rooms close.

**Test.** `CallsRevokeIT`: accept a call, deactivate the callee, and assert that `DeleteRoom` or
`RemoveParticipant` was called on a fake RoomService, and that `call_status` reports `ended`. Ops smoke test
(`deploy/ci/call-smoke`): after `call_end`, a reconnect with the old token must fail. Manual: M-CAL-17.

## R4: Token grants are too broad (High)

**What the contract allows.** The grant is `canPublish:true, canSubscribe:true` with no source restriction. In
LiveKit, that allows publishing **camera, microphone, screen share, and data packets** (`canPublishData` defaults
to true). So:
* A caller who rang for an **audio** call can publish a camera track. If the callee's client renders remote video
  whenever a track arrives, an unwanted video appears on a stranger's screen: indecent exposure on a health app.
* Screen share and data messages are an unmoderated side channel. Through it a caller can send links, files
  disguised as text, or abuse, none of which is reported anywhere.

**Fix.** Build the grant from the call type:
* audio: `canPublishSources:["microphone"]`;
* video: `canPublishSources:["camera","microphone"]`;
* always: `canPublishData:false`, `canUpdateOwnMetadata:false`, `hidden:false`, `recorder:false`, no
  `roomAdmin`, `roomCreate`, `roomList` or `roomRecord`.

Clients must also never render remote video in an audio call. If the product later wants "turn on video", add an
explicit `call_upgrade` action that the other side accepts and that issues new tokens.

**Test.** Unit-test the JWT claims per call type. Ops smoke test: publishing a camera track with an audio token is
rejected by the server. Manual: M-CAL-14.

## R5: IDOR on callId, refId and peerUid (High; must be proven, not assumed)

Every call action takes client-supplied ids. A single missing check means anyone can ring anyone, with health
context attached (the callee sees "অ্যাপয়েন্টমেন্ট" or "রক্তের রিকোয়েস্ট"). Rules that need an explicit test each:
1. `call_start`: the caller is a party to **this** `refId`, and `peerUid` is the counterpart on **this** `refId`.
   That means the donor's response is on this request, the assistant is on this appointment's doctor's list, and
   the offer is on this ambulance request. It is not enough that the peer is the counterpart on *some* record.
   The caller's role is derived from the record, never from the body.
2. `order`: `refId` is "an rx order id **or** a lab order id". If the two id spaces can ever overlap, one lookup
   may match the wrong table, or the first match may be checked against the wrong owner. **Fix:** split the
   context into `rx_order` and `lab_order`, or require prefixed ids, and look up only the named table.
3. `call_accept` and `call_decline`: only the stored callee. `call_cancel`: only the stored caller.
   `call_end` and `call_status`: only the two parties (plus the doctor's current assistants for appointment
   rooms). Each check reads the stored row, never the request. Everyone else gets 404 with the same body.
4. `appt_join`: the patient of that appointment (not a caregiver via `asUid`), its doctor, or a *current*
   assistant of that doctor.
5. `appt_pay_claim` / `appt_pay_confirm`: the same ownership checks. A doctor must not be able to confirm
   another doctor's appointment.
6. `callId` must be random (UUID v4 or ≥ 128-bit). It must not be a sequence, because it is also the room name.

**Test.** `CallsAuthzIT`: a matrix of every action against {caller, callee, an unrelated user, the counterpart
on another refId, a demo user, a caregiver via asUid, the assistant of another doctor}, plus rx/lab id collision
fixtures. Manual: M-CAL-15, M-CAL-16, M-CAL-19, M-CAL-21.

## R6: Public `note` on online chambers, and the demo doctor (High; Critical while demo is live)

`bkashNumber` is correctly hidden from the public `providers` list. The free-text `note` (≤ 200 chars), however,
**is** returned publicly. Free text can carry a phone or bKash number ("ফি আগে পাঠান 01…"). Two problems follow:
* Production still has the demo world (03a S1): anyone can log in as "ডা. ডেমো হাসান" (approved badge) without a
  code, create an online chamber, and put their own bKash number in the note. Real patients in Gazipur see that
  listing (no world filter on `providers`, S1 item 3). Booking across worlds is blocked (`book` checks
  `sameWorld`), but the note alone is enough for a "send the fee first" scam that wears a verified-doctor badge.
* A real doctor's hijacked account can change `bkashNumber`. The value is copied at booking, which protects
  existing bookings but not new ones. Nobody is notified of the change.

**Fix.**
1. Remove the demo world before release (S1, already release-blocking). Until then, keep demo providers out of
   real users' lists, and disable online chambers for demo uids in production.
2. Show `note` only to booked patients (same visibility as `bkashNumber`). Alternatively, reject notes that
   contain 10+ digits or `01\d{9}`.
3. Log every `bkashNumber` change with the time and the old and new last 3 digits. Push the doctor "আপনার bKash
   নম্বর বদলানো হয়েছে". Show the history to admins. After a change, require a 24 h cool-off before new bookings
   show the new number, or require admin re-approval.

**Test.** `OnlineChamberIT`: `providers` never contains `bkashNumber`, and a note containing a phone number is
rejected (or hidden). Manual: M-ONC-01, M-ONC-03, M-ONC-06.

## R7: Fake bKash TrxID claims (Medium)

`payRef` is free text of up to 30 characters, checked only for length. A patient can type anything, reuse a real
TrxID from an earlier payment, or reuse another patient's TrxID from a screenshot. The real defence is the
doctor checking his own bKash app, so the UI must push him to do that.

**Fix.**
1. Validate the format: a bKash TrxID is 10 uppercase alphanumerics. Accept an empty value (claims stay optional)
   or a valid one.
2. Keep TrxIDs **unique per doctor**: a TrxID already used on any of that doctor's appointments returns 409
   `pay_ref_used`.
3. Make the doctor's confirm dialog say "bKash অ্যাপে এই TrxID আর ৳<fee> মিলিয়ে দেখুন". Show claim time and fee
   next to the TrxID.
4. Rate-limit claims to 5 per appointment per hour.

**Test.** Unit test the format. `OnlineChamberIT` for duplicate TrxID → 409. Manual: M-ONC-08, M-ONC-10.

## R8: A doctor marks unpaid or denies service after payment (Medium)

There is no "un-pay" action (`appt_pay_claim` moves only unpaid/claimed → claimed, and paid is final), which is
good. A doctor can still take the fee, then never tap "ফি পেয়েছি", cancel the appointment ("নেওয়া হয়নি"), or simply
not join. The patient has paid outside Lifolo and has no record or recourse. The reverse also happens: a patient
claims, the doctor joins unpaid, and there is no record either way.

**Fix.**
1. Write an append-only audit row for every pay transition (`appt_pay_log`: who, when, from → to, payRef).
2. When a doctor cancels or rejects an appointment that is `claimed` or `paid`, require a reason. Push the patient
   with "টাকা ফেরতের জন্য ডাক্তারের সাথে যোগাযোগ করুন / সাহায্য ও আপিল", and create a support ticket that links
   the appointment and its pay log automatically.
3. Never gate the patient's `appt_join` on `payState`. The contract already says this; keep it.
4. When a slot passes with `paid` and the doctor never joined, auto-flag it to admins and offer the patient
   "অভিযোগ করুন".
5. The terms and the in-app text must state that Lifolo does not hold or refund the fee.

**Test.** `OnlineChamberIT`: a doctor cancelling a paid appointment without a reason → 400. A ticket is
created. The pay log has rows. Manual: M-ONC-09, M-ONC-10, M-ONC-11.

## R9: Lock-screen notifications leak names and context (Medium)

`call_incoming` carries the caller's name and `ctx` and is shown on the lock screen (full-screen intent).
`call_missed` and `appt_call_ready` ("ডাক্তার ভিডিও কলে আছেন — যুক্ত হোন") are ordinary notifications. If the title
or body includes the context ("রক্তের রিকোয়েস্ট", "অ্যাপয়েন্টমেন্ট — ডা. …, মনোরোগ বিশেষজ্ঞ"), anyone holding the
phone learns about a health matter. The same applies to the web Notification API.

**Fix.**
1. Android: use `VISIBILITY_PRIVATE` with a `publicVersion` of "Lifolo-তে কল আসছে" / "Lifolo: নতুন খবর" for
   `call_missed` and `appt_call_ready`.
2. The incoming-call screen may show the caller's name, which is needed to decide whether to answer. It should
   not show the context, specialty, blood group, hospital or anything else health-related until unlocked.
3. Push titles and bodies never contain the context or specialty. Everything else is fetched after unlock through
   `call_status`.
4. The web shows the generic text in the system notification and the details only inside the page.

**Test.** `notify.sh`-style Android check of the notification extras and visibility. Manual: M-CAL-26, M-CAL-64.

## R10: Minors (Medium)

Lifolo has no age field and no 18+ gate. A minor can be a blood requester (for a parent) or a patient. With R1,
an unknown adult can video-call them. Donors must be 18+ by Bangladeshi practice, but requesters and patients
have no such limit.

**Fix.**
1. Ask once for "আমার বয়স ১৮ বা তার বেশি" before the first in-app call, or record it at signup.
2. For accounts that do not confirm it, allow only audio calls with the doctor or assistant of their own
   confirmed appointment. Turn off blood, ambulance and order calls for them; the phone buttons still work.
3. Minors who are family members of a caregiver account are never reachable (calls are not available via
   `asUid`). Keep that.
4. Add the R1 block and report options.
5. Mention minors in the privacy policy section on calls.

**Test.** `CallsRelationIT` case for an unconfirmed-age account. Manual: covered by M-CAL-21 for caregivers.

## R11: Stale "busy" and ghost rooms (Medium)

An accepted call counts as busy until `call_end` or 2 h after it was answered. If both apps die (crash, battery,
network loss, tab closed), nobody calls `call_end`. Both users then get 409 `call_busy` for up to 2 h. That
matters for doctors on an online chamber day and for ambulance drivers. Ringing also counts as busy, which feeds
R2.

**Fix.**
1. Use LiveKit webhooks (`participant_left`, `room_finished`) to mark the call `ended`.
2. Alternatively, make `busy` mean "currently connected according to `RoomService.ListParticipants`", checked when
   the next call comes in.
3. Close rooms with `empty_timeout`/`departure_timeout` (R3).
4. The ringing-as-busy rule should not block `appt_join`.

**Test.** IT with a fake webhook: `participant_left` for both → `ended` → a new call succeeds. Manual:
M-CAL-46, M-CAL-60.

## R12: Data retention, logs, and the exposed VPS (Medium)

The backend keeps call metadata (who, whom, context, time, video, duration) for 180 days and deletes it when
either account is deleted. That is reasonable, but:
* The metadata is health-adjacent ("X called Dr Y, psychiatrist, on appointment Z"). The privacy policy is
  already out of date (S4) and says nothing about calls, LiveKit, the call VPS's country, or "no recording".
* LiveKit and TURN log participant identity (the uid), room names and client IPs. journald on the call VPS keeps
  them with no stated limit.
* `call.lifolo.com` is DNS-only (no Cloudflare), so the VPS's origin IP is public and exposed to scanning and
  DDoS.

**Fix.**
1. Update the privacy policy: calls are not recorded; metadata is kept 180 days; the media server and its
   country are named; TURN relay is used.
2. Set journald `MaxRetentionSec=14day` on the call VPS. Set the LiveKit log level to `info` without
   per-packet logs, and no webhook payload logging.
3. Firewall only 443/tcp, 7881/tcp, the RTC UDP range and 3478/udp (TURN). Use SSH key-only with fail2ban.
   Keep the LiveKit HTTP API off any public port except through the 443 proxy.
4. Use an API secret of at least 32 random bytes, with separate keys for staging and production (ops-calls'
   `livekit-keys.js` derives them; confirm they are not shared).
5. Admin access to call metadata is logged in `admin_log`.

**Test.** Ops: `ops-timers-check`-style check of journald retention and an open-ports scan of the call VPS.

## R13: Room-name or callId guessing (Low)

A room name is useless without a token signed with the API secret, and LiveKit does not let clients create rooms
without a grant. The risk is low *if* R5.6 (random callId) and R12.4 (a strong secret that never leaves the
server) hold. Clients must never receive the API key or secret, only the JWT.

## R14: Presence leak via call_busy and call_off (Low)

`call_busy` ("উনি এখন অন্য কলে আছেন") and `call_off` reveal whether the peer is on a call or has turned calls off.
This is visible only to related parties, so it is acceptable. Keep the messages exactly as specified, and never
add the peer's phone number to the `call_off` response. The caller UI should use only a number the context already
shows (accepted donor, accepted driver, the shop's public phone).

---

## Things the contract already gets right
No recording or egress grants; calls only between related parties; no phone numbers in payloads; demo and real
worlds separated; deactivated, deleting and disabled accounts refused; caregivers cannot call as the patient;
`bkashNumber` kept out of the public list; values copied at booking; the patient's join is not gated on payment;
pushes are FCM data-only with a 45 s TTL, so a stale ring is not delivered late; metadata is deleted with the
account.
