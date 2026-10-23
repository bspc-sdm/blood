## ✅ অ্যাপের কল চালু (মূল VPS-এ, পরীক্ষার পর্ব)


| কী | অবস্থা |
|---|---|
| LiveKit | 1.13.10 · সার্ভিস active |
| signaling | `wss://lifolo.com/rtc` → ✅ (বাইরে থেকে) · `success HTTP 200` |
| TURN/UDP 3478 (বাইরে থেকে STUN) | ✅ ok 20.169.63.242:1040 |
| ICE-TCP 7881 (বাইরে থেকে) | ✅ open |
| মিডিয়া UDP 50000-60000 | ফায়ারওয়ালে খোলা (active; livekit rules 3) — নিচের কল-পরীক্ষাই প্রমাণ |
| TURN/TLS | নেই (মূল VPS-এ আলাদা DNS-only নাম নেই) — খুব কড়া নেটওয়ার্কে কল না-ও লাগতে পারে |
| সীমা | CPU 150% of 6 cores, RAM 512M; ঘরে সর্বোচ্চ ২ জন |
| অ্যাপের সেটিং | LIVEKIT_URL=`wss://lifolo.com` + key/secret (VPS_ROOT_PASSWORD থেকে) — VPS-এর `/etc/livekit/backend.env`; app.env: in app.env |

### আসল কলের পরীক্ষা (দুটো headless Chromium, নকল ক্যামেরা/মাইক)

| পরীক্ষা | ফল | A-র পথ | B-র পথ | A পেল (অডিও/ভিডিও) | B পেল (অডিও/ভিডিও) | সময় | মন্তব্য |
|---|---|---|---|---|---|---|---|
| সরাসরি (UDP/TCP) | ✅ | prflx/udp | srflx/udp | 14 KB / 50 KB | 14 KB / 65 KB | 10 s | ৩য় জন: ঘর ভরা, ফেরানো হলো ✅ (খালি ঘরে ঠিকই ঢোকে) |
| শুধু TURN/UDP 3478 (relay) | ✅ | relay/udp | relay/udp | 13 KB / 43 KB | 13 KB / 66 KB | 8 s |  |

সার্ভারের দেওয়া ICE/TURN ঠিকানার ধরন: `turn:<host>:3478?transport=udp`

_run 39885678546 · গাইড: deploy/CALLS-SERVER.md_
