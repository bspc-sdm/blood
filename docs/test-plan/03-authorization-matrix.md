# ৩. অনুমতির ম্যাট্রিক্স (authorization) — কোড যেমন মানে

> এই ফাইল কোড থেকে বানানো: `python3 docs/test-plan/tools/build_inventory.py` (হাতে বদলাবেন না — RC-র কোডে আবার চালান)।


কীভাবে বানানো: সব action-এর জন্য (১) কেন্দ্রীয় নিয়ম কোড থেকে — অতিথি = `ActionPolicy.PUBLIC_ACTIONS`, কেয়ারগিভার = `CARE_ACTIONS` (`asUid`), অ্যাডমিন কুকি = `admin_*` + `WEB_PANEL_READS` + `web_logout` (`CommunityController.webUser`); (২) হ্যান্ডলারের নিজের চেক — Kotlin বডিতে খোঁজা markers আর `API-INVENTORY.md`-এর auth লেখা মিলিয়ে।

সংকেত: **Y** = চলে · **own** = চলে কিন্তু শুধু নিজের সারি (অন্যের id দিলে 404/403/খালি) · **own shop / own lab** = নিজের অনুমোদিত দোকান/প্যাথলজি · **for listed doctor** = যে ডাক্তার সহকারী হিসেবে ইমেইল যোগ করেছেন · **as patient** = কেয়ারগিভার রোগীর হয়ে (সক্রিয় care link) · **any** = অন্যেরটাও (অ্যাডমিন) · **N401** = লগইন লাগবে · **N403** = নিষেধ (কিছু ক্ষেত্রে 404 দিয়ে লুকানো)।

রোল কলাম: Guest · User · Caregiver(asUid) · Doctor · Assistant · Nurse/pro · Pharmacy · Lab · Ambulance · BloodBank · Admin · Owner · AdminCookie। "User" = যেকোনো লগইন করা মানুষ (রোগী, রক্তদাতা)। ব্যবসার রোলও একজন User — তাই User-এ Y মানে সবার Y।

> ⚠️ এটা স্থির বিশ্লেষণ (static)। Phase 2-এ প্রতিটা সারি একটা স্বয়ংক্রিয় টেস্টে প্রমাণ করতে হবে (`AuthorizationMatrixIT` — ০৬-এ)। সন্দেহজনক জিনিসগুলো আলাদা ফাইলে: [03a-security-findings.md](03a-security-findings.md)।

| action | Guest | User | Caregiver(asUid) | Doctor | Assistant | Nurse/pro | Pharmacy | Lab | Ambulance | BloodBank | Admin | Owner | AdminCookie | doc auth | রিভিউ-সংকেত |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `admin_add` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | owner only | owner web-panel |  |
| `admin_catalog_set` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_list` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | owner only | owner (ADMIN_EMAILS, verified email) web-panel |  |
| `admin_log` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_overview` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_remove` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | owner only | owner web-panel |  |
| `admin_report_resolve` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_reports` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_request_docs` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_stats` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_ticket` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_ticket_reply` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_tickets` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_user` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_user_action` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | admin (owner for actions on admins/owner) web-panel |  |
| `admin_user_find` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_user_section` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_verify_docs` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `catalog_get` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | guest web-panel |  |
| `report` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `ticket_create` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `ticket_reply` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (ticket owner) |  |
| `tickets_mine` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `verify_docs_upload` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (listing owner) |  |
| `admin_ambulance_resolve` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_ambulances` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `amb_facilities` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `ambreq_accept` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (driver the request was sent to) |  |
| `ambreq_agree` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (patient) |  |
| `ambreq_budget` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (patient) |  |
| `ambreq_byphone` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (patient) |  |
| `ambreq_cancel` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (patient) |  |
| `ambreq_count` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `ambreq_create` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `ambreq_decline` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (driver) |  |
| `ambreq_driver` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `ambreq_replace` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (patient) |  |
| `ambreq_status` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (patient of request) |  |
| `ambreq_step` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (accepted driver) |  |
| `ambulance_available` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (owner) |  |
| `ambulance_called` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `ambulance_delete` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (owner) |  |
| `ambulance_mine` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `ambulance_rate` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `ambulance_save` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `ambulance_stats` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `ambulances` | Y | own | N403 | own | own | own | own | own | own | own | own | own | N403 | guest |  |
| `maps_link_resolve` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `appointment_late` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient of appointment) caregiver |  |
| `appointment_late_reply` | N401 | N403 | N403 | own | for listed doctor | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor(chamber)\|assistant |  |
| `appointment_queue` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient of appointment) caregiver |  |
| `appointment_update` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (provider: confirm/decline needs badge; patient or provider: cancel) caregiver |  |
| `book` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user role:badge caregiver |  |
| `my_appointments` | N401 | Y | as patient | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user caregiver |  |
| `provider_me` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `provider_save` | N401 | N403 | N403 | Y | N403 | Y | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:badge (doctor/nurse/nutritionist/trainer); phone-verified when enabled |  |
| `providers` | Y | Y | as patient | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest caregiver |  |
| `queue_control` | N401 | N403 | N403 | own | for listed doctor | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor(chamber)\|assistant |  |
| `queue_today` | N401 | N403 | N403 | own | for listed doctor | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor(chamber)\|assistant |  |
| `web_logout` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | Y | web session cookie (any) web-panel |  |
| `admin_bloodbank_add` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_bloodbank_delete` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_bloodbank_edit` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_bloodbank_resolve` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_bloodbanks` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_districts` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_emergencies` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_emergency_close` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_emergency_create` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_emergency_group` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_emergency_more` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `blood_contact_get` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (requester, or accepted/donated donor of open request) |  |
| `blood_request_get` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (same world) |  |
| `blood_requests_mine` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `blood_requests_near` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `blood_response_get` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `blood_responses_list` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (requester) |  |
| `bloodbank_called` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `bloodbank_delete` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (owner) |  |
| `bloodbank_mine` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `bloodbank_save` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `bloodbank_stats` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `bloodbanks` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest |  |
| `confirm_donor` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (requester) |  |
| `donation_date_set` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `emergencies_active` | Y | own | N403 | own | own | own | own | own | own | own | own | own | N403 | guest |  |
| `emergency_donated` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `emergency_respond` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `guest_requests` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest |  |
| `report_request` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `request_create` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `request_delete` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (requester) |  |
| `request_reopen` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (requester) |  |
| `response_update` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (donor: cancel/donated; requester: remove) |  |
| `books_adjust` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_adjusts` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_item_delete` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_item_save` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_item_stock` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_items` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_purchase_add` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_purchase_delete` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_purchases` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_report` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_rx_preview` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_sale_add` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_sale_delete` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_sales` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `books_today` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `care_invite` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `care_list` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `care_remove` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `care_respond` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `family_get` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user caregiver |  |
| `family_set` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `admin_badge_resolve` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_badges` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_hospital_requests` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_hospital_resolve` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_hospitals` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `answer` | N401 | N403 | N403 | Y | N403 | Y | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:badge |  |
| `ask` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `badge_apply` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `delete_answer` | N401 | own | N403 | own | own | own | own | own | own | own | any | any | N403 | user (author) or admin |  |
| `delete_question` | N401 | own | N403 | own | own | own | own | own | own | own | any | any | N403 | user (author) or admin |  |
| `delete_tip` | N401 | own | N403 | own | own | own | own | own | own | own | any | any | N403 | user (author) or admin |  |
| `edit_answer` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (author, badge still approved) |  |
| `edit_question` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (author) |  |
| `edit_tip` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (author, badge approved) |  |
| `hospital_request` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `hospitals` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest |  |
| `me` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `post_tip` | N401 | N403 | N403 | Y | N403 | Y | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:badge |  |
| `question` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest |  |
| `questions` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest |  |
| `tips` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest |  |
| `admin_demo_seed` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | owner only | owner web-panel |  |
| `admin_demo_status` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | owner only | owner web-panel |  |
| `admin_demo_wipe` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | owner only | owner web-panel |  |
| `home_called` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `home_providers` | Y | own | N403 | own | own | own | own | own | own | own | own | own | N403 | guest |  |
| `home_rate` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `home_stats` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `admin_lab_resolve` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_labs` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_labtests_set` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `lab_delete` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user (lab owner) |  |
| `lab_form` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_home_cancel` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient of order) caregiver |  |
| `lab_home_change` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient of order) caregiver |  |
| `lab_home_confirm` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_home_decline` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_me` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `lab_open_code` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_order_get` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient, lab, or doctor when paid/no fee → else 402) caregiver |  |
| `lab_order_status` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_orders` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_prices_get` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_prices_public` | Y | Y | as patient | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest caregiver |  |
| `lab_prices_set` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_ranges_set` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_report` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | own lab | N403 | N403 | N403 | N403 | N403 | user role:approved-lab |  |
| `lab_save` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `labs` | Y | Y | as patient | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest caregiver |  |
| `labtests_get` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | Y | user (also web-panel, caregiver) |  |
| `review_confirm` | N401 | N403 | N403 | own | for listed doctor | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor\|assistant |  |
| `review_done` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `review_pay` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient of review) caregiver |  |
| `review_settings` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `reviews_doctor` | N401 | N403 | N403 | own | for listed doctor | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor\|assistant |  |
| `rx_lab_options` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user caregiver |  |
| `rx_send_lab` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user caregiver |  |
| `device_register` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `device_unregister` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `inbox` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `inbox_read` | N401 | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user |  |
| `admin_pharmacies` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `admin_pharmacy_resolve` | N401 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | Y | Y | Y | user admin web-panel |  |
| `drug_search` | Y | Y | N403 | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | guest |  |
| `med_find` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `med_find_answer` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user (approved pharmacy) |  |
| `med_find_close` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (finder) |  |
| `med_find_requests` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user (approved pharmacy) |  |
| `med_finds_mine` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `pharmacies` | Y | own | as patient | own | own | own | own | own | own | own | own | own | N403 | guest caregiver |  |
| `pharmacy_called` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `pharmacy_delete` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user (pharmacy owner) |  |
| `pharmacy_find_setting` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user (pharmacy owner) |  |
| `pharmacy_me` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `pharmacy_pause` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user (pharmacy owner) |  |
| `pharmacy_rate` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `pharmacy_save` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `pharmacy_stats` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `rx_dispense` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_missed_clear` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_order_update` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_order_withdraw` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient of order) caregiver |  |
| `rx_orders_pharmacy` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_pharmacy_home` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_pharmacy_online_set` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_pharmacy_open` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_pharmacy_options` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user caregiver |  |
| `rx_send_pharmacy` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user caregiver |  |
| `rx_shop_find` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user caregiver |  |
| `stock_get` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `stock_set` | N401 | N403 | N403 | N403 | N403 | N403 | own shop | N403 | N403 | N403 | N403 | N403 | N403 | user role:approved-pharmacy |  |
| `rx_activity` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient of rx) caregiver |  |
| `rx_assisting` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (verified email listed as assistant) |  |
| `rx_cancel` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user (writing doctor) |  |
| `rx_claim` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user caregiver |  |
| `rx_create` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `rx_doctor_list` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `rx_get` | N401 | own | as patient | own | own | own | own | own | own | own | own | own | N403 | user (patient or doctor of rx) caregiver |  |
| `rx_me` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `rx_mine` | N401 | Y | as patient | Y | Y | Y | Y | Y | Y | Y | Y | Y | N403 | user caregiver |  |
| `rx_patient_add` | N401 | N403 | N403 | own | for listed doctor | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor\|assistant |  |
| `rx_patient_history` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `rx_patients` | N401 | N403 | N403 | own | for listed doctor | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor\|assistant |  |
| `rx_team` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `rx_team_set` | N401 | N403 | N403 | own | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | N403 | user role:doctor |  |
| `delete_account` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `phone_send_code` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `phone_verify_code` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user |  |
| `profile_get` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (not guest, not caregiver) |  |
| `profile_update` | N401 | own | N403 | own | own | own | own | own | own | own | own | own | N403 | user (not guest, not caregiver) |  |

## অন্য endpoint

| endpoint | কে পারে |
|---|---|
| `/.well-known/assetlinks.json` | সবাই |
| `/api/admin/corrections` | অ্যাডমিন (Bearer বা কুকি) |
| `/api/admin/resolve` | অ্যাডমিন (Bearer বা কুকি) |
| `/api/auth/send-code` | সবাই (IP/ইমেইল সীমা) |
| `/api/auth/verify-code` | সবাই; `web:true` শুধু অ্যাডমিন ইমেইল; `demo`/`demoRoles` — **ডেমো চালু থাকলে যে কেউ, কোড ছাড়া** |
| `/api/close-request` | Bearer — রক্তপ্রার্থী |
| `/api/community` | ওপরের ম্যাট্রিক্স |
| `/api/cron/daily` | `Bearer $CRON_SECRET` (GET) |
| `/api/cron/sentry-test` | `Bearer $CRON_SECRET` |
| `/api/cron/storage` | `Bearer $CRON_SECRET` |
| `/api/cron/storage/migrate` | `Bearer $CRON_SECRET` |
| `/api/cron/storage/probe` | `Bearer $CRON_SECRET` |
| `/api/edit-request` | Bearer — রক্তপ্রার্থী |
| `/api/events` | Bearer — শুধু নিজের `user:{uid}` স্ট্রিম |
| `/api/health` | সবাই |
| `/api/notify-request` | Bearer — রক্তপ্রার্থী নিজে |
| `/api/respond` | Bearer — যোগ্য ডোনার (একই world, গ্রুপ মেলে) |
| `/api/share` | সবাই |
| `/r/{id}` | সবাই |
| `/rx/{code}` | সবাই (কোড জানলে) |
| `/shop/{code}` | সবাই |
