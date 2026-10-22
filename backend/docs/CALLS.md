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
