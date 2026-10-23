# Web e2e — spring ✅

যাত্রা 13 (থেমেছে 0) · দৃশ্য 247/247 পাস (0 ব্যর্থ) · ধাপ 529 (ভুল 0) · API 5xx 0 · সার্ভার ERROR 0 · নিজে ধরা সন্দেহ 2 · জানা ভুল 0 · API action ডাকা 256 (সফল 254) · পুশ যাচাই 31 (22 ধরন)

| যাত্রা | দৃশ্য পাস | ধাপ | ভুল ধাপ | থেমেছে | সময় |
|---|---|---|---|---|---|
| 00-demo-seed | 0/0 | 4 | 0 | — | 12s |
| 01-core-journey | 29/29 | 85 | 0 | — | 218s |
| 02-blood | 30/30 | 77 | 0 | — | 194s |
| 03-ambulance | 7/7 | 18 | 0 | — | 49s |
| 04-caregiver | 10/10 | 35 | 0 | — | 101s |
| 05-services | 35/35 | 77 | 0 | — | 217s |
| 06-prescription | 26/26 | 62 | 0 | — | 185s |
| 07-health-profile | 22/22 | 52 | 0 | — | 149s |
| 08-more | 17/17 | 43 | 0 | — | 154s |
| 09-ambulance-call | 10/10 | 29 | 0 | — | 146s |
| 10-admin-support | 9/9 | 36 | 0 | — | 188s |
| 11-notifications | 38/38 | 9 | 0 | — | 217s |
| 12-more-actions | 14/14 | 2 | 0 | — | 10s |

## নোটিফিকেশন — ধরন, শিরোনাম, চাপলে কোথায় (Android / ওয়েব)

| যাচাই | ধরন | কার কাছে | শিরোনাম | Android | ওয়েবে খুলল |
|---|---|---|---|---|---|
| PUSH-request | request | e2e-push-donor | B- রক্ত দরকার | RequestDetail(LXfG0YBKxv6a9mvIXk6S) | /request/LXfG0YBKxv6a9mvIXk6S |
| PUSH-response | response | e2e-push-patient | একজন ডোনার রাজি হয়েছেন | MyRequestDetail(LXfG0YBKxv6a9mvIXk6S) | /my-requests/LXfG0YBKxv6a9mvIXk6S |
| PUSH-request-confirm | request | e2e-push-donor | আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন | RequestDetail(LXfG0YBKxv6a9mvIXk6S) | /request/LXfG0YBKxv6a9mvIXk6S |
| PUSH-request_removed | request_removed | e2e-push-donor | রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে | Main(HOME) | / |
| PUSH-emergency | emergency | e2e-push-donor | জরুরি: B- রক্ত দরকার | Main(HOME) + emergency banner | / |
| PUSH-care-invite | care | e2e-push-donor | ই২ই রোগী রুবেল আপনাকে কেয়ারগিভার করতে চান | Care | /care |
| PUSH-care-accepted | care | e2e-push-patient | ই২ই ডোনার দীপা এখন আপনার কেয়ারগিভার | Care | /care |
| PUSH-support | support | e2e-push-patient | Lifolo টিমের উত্তর এসেছে | Help | /help/UEJPtoqQfEOqi7dEA421 |
| PUSH-hospital | hospital | e2e-push-patient | আপনার চাওয়া হাসপাতাল তালিকায় যোগ হয়েছে | Main(HOME) | / |
| PUSH-badge | badge | e2e-push-patient | আপনার ব্যাজ-এর কাগজের ছবি দিন | Main(HOME) | /health/badge |
| PUSH-services-listing | services | ambulance | আপনার অ্যাম্বুলেন্স-এর কাগজের ছবি দিন | MyBusiness(ambulance) | /work/ambulance |
| PUSH-services-amb | services | ambulance | অ্যাম্বুলেন্স দরকার — প্রায় ২ কিমি দূরে | MyBusiness(ambulance) | /work/ambulance |
| PUSH-amb_req_mine | amb_req_mine | caregiver | ডেমো জীবন অ্যাম্বুলেন্স (এসি) ৳১১০০-তে আসতে রাজি | AmbulanceRequest(QTPQkVujYkvzFu4a2kHh) | /services/ambulance/call/QTPQkVujYkvzFu4a2kHh |
| PUSH-services-amb-agreed | services | ambulance | রোগী রাজি — ৳১১০০। এখনই রওনা দিন। | MyBusiness(ambulance) | /work/ambulance |
| PUSH-services-amb-cancel | services | ambulance | রোগী অ্যাম্বুলেন্সের অনুরোধ বাতিল করেছেন | MyBusiness(ambulance) | /work/ambulance |
| PUSH-answer | answer | patient | আপনার প্রশ্নের উত্তর এসেছে | QuestionDetail(Tp2NiF97Aa8Ldyowy1QX) | /health/question/Tp2NiF97Aa8Ldyowy1QX |
| PUSH-appointment-provider | appointment | doctor | নতুন অ্যাপয়েন্টমেন্টের অনুরোধ | ChamberEdit | /work/pro |
| PUSH-appointment-patient | appointment | donor | অ্যাপয়েন্টমেন্ট নেওয়া হয়নি | MyAppointments | /my-appointments |
| PUSH-pay_requested | pay_requested | patient | পেমেন্ট আপডেট — লাইফলো | Pay(7F62JsMhfmaqGIC5Fatd) | /invoices/payments/7F62JsMhfmaqGIC5Fatd |
| PUSH-invoice_new | invoice_new | patient | ডা. ডেমো হাসান থেকে নতুন রসিদ | Invoice(sN3Ej4QQiq5AwctXijqy) | /invoices/sN3Ej4QQiq5AwctXijqy |
| PUSH-rx | rx | patient | ডা. ডেমো হাসান নতুন প্রেসক্রিপশন দিয়েছেন | Rx(zJ5I9uR2SxpD8DN7pPHJ) | /services/pharmacy/prescriptions/zJ5I9uR2SxpD8DN7pPHJ |
| PUSH-care-helper | care | caregiver | ডেমো রোগী রহিম: ডা. ডেমো হাসান নতুন প্রেসক্রিপশন দিয়েছেন | CareFor(demo-patient) › Rx(zJ5I9uR2SxpD8DN7pPHJ) | /services/pharmacy/prescriptions/zJ5I9uR2SxpD8DN7pPHJ |
| PUSH-rx_pharmacy | rx_pharmacy | pharmacy | নতুন অনলাইন অর্ডার | MyBusiness(pharmacy) › orders | /work/pharmacy/orders |
| PUSH-rx_lab | rx_lab | lab | নতুন পরীক্ষা — বাসা থেকে নমুনা | MyBusiness(lab) › orders | /work/lab/orders |
| PUSH-rx-home-confirm | rx | patient | ডেমো সিটি ডায়াগনস্টিক সেন্টার কাল সকাল ৮টায় আসবে | Rx(zJ5I9uR2SxpD8DN7pPHJ) | /services/pharmacy/prescriptions/zJ5I9uR2SxpD8DN7pPHJ |
| PUSH-rx-report | rx | patient | আপনার পরীক্ষার রিপোর্ট এসেছে | Rx(zJ5I9uR2SxpD8DN7pPHJ) | /services/pharmacy/prescriptions/zJ5I9uR2SxpD8DN7pPHJ |
| PUSH-rx_review | rx_review | doctor | রিপোর্ট দেখার টাকা মিলিয়ে নিন | RxReviews | /work/pro |
| PUSH-med_find | med_find | pharmacy | কাছে একজন ওষুধ খুঁজছেন | MyBusiness(pharmacy) › finds | /work/pharmacy/finds |
| PUSH-med_find_mine | med_find_mine | patient | ডেমো লাইফ ফার্মেসি-এ আছে | Main(SERVICES) › pharmacy › medicine finder | /services/pharmacy/medicine |
| PUSH-correction | correction | demo-x-farhana | তারিখ সংশোধনের অনুরোধ বাতিল হয়েছে | Main(HOME) | — |
| PUSH-eligible | eligible | e2e-push-donor | আপনি আবার রক্ত দিতে পারবেন | Main(HOME) | / |

## API-র ৪xx/৫xx (সংখ্যা)

- 63× 404 /api/community pay_request_get পাওয়া যায়নি
- 10× 401 /api/community admin_overview লগইন দরকার
- 2× 403 /api/community blood_responses_list এটা আপনার রিকোয়েস্ট না
- 2× 409 /api/community book সেদিন এই ডাক্তারের কাছে আপনার অনুরোধ আগে থেকেই আছে
- 1× 409 /api/community rx_send_lab এই প্যাথলজিতে আগেই পাঠানো আছে
- 1× 400 /api/community review_settings টাকা পাঠানোর মোবাইল নম্বর দিন
- 1× 400 /api/community rx_team_set নিজেকে সহকারী হিসেবে যোগ করা যায় না
- 1× 400 /api/community rx_claim প্রোফাইলের বা পরিবারের সদস্যের মোবাইল নম্বরটাই দিন
- 1× 409 /api/community lab_open_code এই প্রেসক্রিপশন বাতিল
- 1× 403 /api/community admin_overview শুধু অ্যাডমিন
- 1× 403 /api/auth/verify-code undefined আপনার অ্যাকাউন্ট Lifolo টিম বন্ধ করেছে। কারণ: ডেমো: ভুয়া রিকোয়েস্ট (পরীক্ষা)। আপিল করতে ইমেইল করুন: ekapkbd@gmail.com
- 1× 428 /api/community profile_update এটা করতে আগে স্বাস্থ্য তথ্য রাখার সম্মতি দিন (অ্যাপ হালনাগাদ করুন, বা lifolo.com-এ লগইন করে দিন)
- 1× 400 /api/community device_register ফোন চেনা যায়নি
- 1× 404 /api/community question প্রশ্নটা পাওয়া যায়নি
- 1× 409 /api/community report_request আগেই রিপোর্ট করা হয়েছে
- 1× 400 /api/community phone_send_code ফোন যাচাই এখনো চালু হয়নি
- 1× 400 /api/community phone_verify_code কোডের মেয়াদ শেষ। নতুন কোড নিন।
- 1× 401 /api/community admin_overview Please log in
- 1× 404 /api/auth/verify-code undefined ডেমো চালু নেই
