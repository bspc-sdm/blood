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
