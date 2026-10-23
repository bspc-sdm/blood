# Lifolo — In-app messages (মেসেজ) — shared contract

The owner approved this on 2026-10-23. Every contact point offers three buttons: **অডিও কল · ভিডিও কল · মেসেজ**.
Messages use the same relation rules, blocking and reporting as calls (see calls CONTRACT / backend/docs/CALLS.md).
The backend-chat agent owns `backend/docs/CHAT.md`, and that file is final once it is on main.

## Who can message whom
- Same relations as calls: blood, appointment, ambulance, rx_order, lab_order.
- Same donor→requester rule: only after the response is confirmed, or after the requester contacted the donor first.
- Same block list. A block stops calls AND messages.
- A thread is per pair + ctx + refId. When the relation ends, the thread becomes read-only. It is deleted 90 days later, photos included.
- Never with deactivated accounts, between demo and real accounts, or with sanctioned users.

## Actions
- `chat_open {ctx, refId, peerUid?}` → `{threadId, peer:{uid, name, photoThumb?}, canSend, messages:[…last 50]}`
- `chat_send {threadId, clientId, text? (≤1000), photo? (jpeg ≤1 MB, base64, stored like other uploads/R2), location? {lat, lng}}` → `{message}`
  - `clientId` makes sends idempotent.
- `chat_messages {threadId, before?}` returns pages of 50.
- `chat_threads {cursor?}` → `{items:[{threadId, ctx, refId, peer, last:{text|"📷"|"📍", at, mine}, unread, canSend}]}`
- `chat_read {threadId, upTo}`. Read receipts are shown as ✓✓.
- `chat_unsend {messageId}`: only your own message, within 10 min. The message then shows as "মেসেজ মুছে ফেলা হয়েছে".
- `chat_report {messageId, reason}` uses the same reasons as call_report.
- `chat_unread` → `{total}` for the badge.
- Message: `{id, threadId, from, text?, photoUrl?, photoThumb?, location?, at, readAt?, unsent?}`

## Delivery
- FCM data push `chat_message {threadId, messageId, from (first name), cat:"messages"}`.
  - The lock screen shows only "লাইফলো মেসেজ — <first name>", never the text.
  - Pushes are collapsed per thread.
- Web: SSE event `chat_message` (same payload) and `chat_read {threadId, upTo}` on /api/events.
- New notify category `messages` (default on). When it's off, messages still arrive, just without a push.

## Limits
- 30 messages per minute per sender.
- 20 photos per day per sender.
- At most 20 messages to a pair before the other side replies.
- 429 `chat_rate`.
- 403 `chat_not_allowed`, 403 `call_blocked` (shared), 409 `chat_closed` (read-only thread).

## Video calls in blood / ambulance / order contexts
The caller may choose ভিডিও কল directly. The callee then sees two answer buttons, **"অডিওতে ধরুন"** and **"ভিডিওতে ধরুন"**.
- If answered with audio, neither side publishes or subscribes to video until both agree (`call_upgrade`).
- Appointment calls are video as normal.
- This replaces "audio-only start" from the security review. The owner wants all three options visible.

---

# Backend — final API (what the server implements; app and web build against THIS section)

Where this section differs from the text above, this section wins. Owner: spring-p18. Relations, the donor rule,
blocks, demo/real and account checks are the shared `ContactPolicy` / `BlockService` of the calls backend
(`backend/docs/CALLS.md`); messages add only a `ContactHistory` (a requester who **messaged** a donor about the request
counts as "contacted first", exactly like a call).

## Actions (`POST /api/community`, logged in; NOT for a caregiver via `asUid`; a deactivated account → 423)
Times are epoch ms. `ctx` ∈ `blood | appointment | ambulance | rx_order | lab_order`.

| action | body | answer |
|---|---|---|
| `chat_open` | `{ctx, refId, peerUid?}` | `{ok, threadId, ctx, refId, peer:{uid,name,photoThumb?}, canSend, blocked?, closed?, messages:[…], before}` |
| `chat_send` | `{threadId, clientId, text?, photo?, location?:{lat,lng}}` | `{ok, message}` |
| `chat_messages` | `{threadId, before?}` | `{ok, messages:[…], before}` |
| `chat_threads` | `{cursor?}` | `{ok, items:[{threadId, ctx, refId, peer, last:{text, at, mine}, unread, canSend}], cursor}` |
| `chat_read` | `{threadId, upTo}` | `{ok, read}` (how many messages were newly marked) |
| `chat_unsend` | `{messageId}` | `{ok, message}` (the message with `unsent:true`) |
| `chat_report` | `{messageId, reason, note?}` | `{ok}` |
| `chat_unread` | — | `{ok, total}` |

* **Message**: `{id, threadId, from (uid), text?, photoUrl?, photoThumb?, location?:{lat,lng}, at, readAt?, unsent?}`.
  An unsent message is `{id, threadId, from, at, unsent:true}` (+`readAt`) — its content is erased on the server;
  apps show "মেসেজ মুছে ফেলা হয়েছে".
* **Paging** (`chat_open`, `chat_messages`): at most 50 messages, **oldest → newest within the page**, ordered by
  `(at, id)` so equal timestamps never drop a message. `before` in the answer is an **opaque cursor** (string) for the
  next older page, or `null` when there is nothing older; pass it back as `before`. `chat_open` returns the newest 50.
* **chat_open**: allowed relation → the thread of (pair, ctx, refId) is created if needed, `canSend:true`. No relation
  any more but a thread exists → that thread read-only (`canSend:false`, `closed:true`). I blocked them →
  `canSend:false`, `blocked:true` (no thread yet → 403 `call_blocked`). No relation and no thread → 403
  `chat_not_allowed`. `peerUid` as in calls (the requester must name the donor; patient → doctor's assistant).
* **chat_send**: `clientId` (1–64 of `A-Za-z0-9_-`, required) makes the send idempotent: the same sender + thread +
  `clientId` again answers the first message (no second push). Exactly one of: `text` (≤ 1000 chars after trimming,
  may also caption a photo), `photo`, `location`. `photo` = base64 JPEG (PNG accepted), ≤ 1 MB decoded; the server
  turns it upright (EXIF orientation), resizes it (≤ 1600 px), re-encodes it as a new JPEG — **no EXIF/GPS or other
  metadata is kept** — and stores it like the other uploads (R2; base64 in PostgreSQL when R2 is not configured).
  `location` = `{lat:-90…90, lng:-180…180}` (rounded to 6 decimals).
* **photoUrl**: a signed URL `…/api/chat/photo/<messageId>?e=<expiry s>&s=<signature>`, valid **1 hour**, fetched with a
  plain GET, **no auth header**. Only thread members ever receive it (in `chat_open` / `chat_messages` / `chat_send` /
  SSE). Expired or tampered → 403; unsent / deleted → 404. When it has expired, refetch the page with `chat_messages`.
  **photoThumb**: a small inline base64 JPEG (≤ 160 px).
* **chat_threads**: threads with at least one message, newest activity first, 30 per page; `cursor` (opaque string or
  `null`) for the next page. `last.text` = the text, `"📷"` (photo), `"📍"` (location) or "মেসেজ মুছে ফেলা হয়েছে" /
  "Message deleted" (unsent). `unread` = the peer's messages I have not read (unsent ones do not count).
* **chat_read**: `upTo` = the `at` of the newest message seen. All of the peer's messages with `at ≤ upTo` get `readAt`
  (✓✓). The peer gets SSE + a silent push `chat_read {threadId, upTo, by}`.
* **chat_unsend**: own message only, within **10 minutes** of `at` → 409 `chat_unsend_window` after that. Text, photo
  (R2 object too) and location are deleted at once. The peer gets SSE `chat_message` with the updated message.
  Unsending again answers the same.
* **chat_report**: the peer's message only; `reason` ∈ `harassment | spam | fake | other` (same as `call_report`),
  `note` ≤ 500 chars. The message content is copied into the report for the Lifolo team. Reporting again = `{ok}`.
* **chat_unread**: `total` = unread messages over all my threads (the badge).

### Errors (standard `{error, code}` body)
| status / code | when |
|---|---|
| 400 `bad_request` | missing/invalid `ctx`, `refId`, `peerUid`, `threadId`, `clientId`, `text`, `location`, `upTo`, `before`, `reason` |
| 400 `bad_photo` | photo not base64 JPEG/PNG, unreadable, or over 1 MB |
| 403 `chat_not_allowed` | no relation, demo ↔ real, peer deactivated / deleting / disabled, the peer blocked me (not revealed) |
| 403 `call_blocked` | I blocked this person (shared with calls; unblock with `call_unblock`) |
| 404 | thread / message not found or not mine |
| 409 `chat_closed` | the relation ended: the thread is read-only |
| 409 `chat_unsend_window` | unsend after 10 minutes |
| 429 `chat_rate` | > 30 messages / minute per sender; > 20 photos / Dhaka day per sender; 20 messages in a row to a thread before the other side replies |

## Delivery
* **FCM** (data-only, high priority, every value a string, `forUid` added as for every push):
  * `chat_message` → the peer: `{type:"chat_message", cat:"messages", threadId, messageId, from:<first name>, title, body:""}`.
    `title` = "লাইফলো মেসেজ — <first name>" / "Lifolo message — <first name>". **Never the text, photo or location.**
    FCM collapse key `chat_<threadId>` (one notification per thread; apps use `threadId` as the notification tag);
    TTL 24 h; no bell-list (inbox) entry.
  * `chat_read` → the peer whose messages were read: `{type:"chat_read", cat:"messages", threadId, upTo, by}` (no
    title; collapse key `chat_read_<threadId>`; TTL 1 h).
* **SSE** on `GET /api/events` (topic `user:{uid}`), to both members (the sender's other devices too):
  * `chat_message` `{threadId, messageId, from, message}` — `message` is the full message (also after an unsend).
  * `chat_read` `{threadId, upTo, by}`.
* **Notify category** `messages` ("মেসেজ", default on, last in the list): `notify_prefs_get` → `cats.messages`;
  `notify_prefs_set {cats:{messages:false}}`. Off (or "সব নোটিফিকেশন" off): messages still arrive (SSE, `chat_unread`,
  `chat_threads`), only the phone push is not sent.

## Threads, retention, accounts
* One thread per pair + `ctx` + `refId`. Every send re-checks the relation; when it has ended the thread is closed
  (read-only, 409 `chat_closed`) — also by the daily job for threads nobody touched. If the same relation becomes
  active again (`chat_open`/`chat_send` allowed), the thread re-opens.
* Daily job step `chatDeleted`: closes ended threads, then deletes threads **closed more than 90 days ago** with
  every message and photo (R2 objects through `stored_objects_trash`).
* Account deletion: all threads of the person are deleted (both sides' messages and photos) when the deletion runs;
  during the 30-day grace and while deactivated the peer sees the thread read-only (`canSend:false`).
* Demo accounts only talk to demo accounts. The demo world (and its daily rebuild) has three threads: the demo donor
  with requester রেহানা (blood), the demo patient with the demo lab (lab_order), the demo pharmacy with a patient
  (rx_order).

## Privacy
Messages are stored on the Lifolo server (photos on Cloudflare R2), **not end-to-end encrypted**; the Lifolo team
reads a message only when it is reported. Deleted 90 days after the conversation became read-only, at once on unsend
(that message) or account deletion. See PRIVACY.md.
