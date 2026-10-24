# Web e2e — spring ❌

যাত্রা 13 (থেমেছে 0) · দৃশ্য 246/247 পাস (1 ব্যর্থ) · ধাপ 530 (ভুল 1) · API 5xx 0 · সার্ভার ERROR 0 · নিজে ধরা সন্দেহ 2 · জানা ভুল 0 · API action ডাকা 254 (সফল 252) · পুশ যাচাই 31 (22 ধরন)

| যাত্রা | দৃশ্য পাস | ধাপ | ভুল ধাপ | থেমেছে | সময় |
|---|---|---|---|---|---|
| 00-demo-seed | 0/0 | 4 | 0 | — | 12s |
| 01-core-journey | 29/29 | 85 | 0 | — | 219s |
| 02-blood | 30/30 | 77 | 0 | — | 194s |
| 03-ambulance | 7/7 | 19 | 0 | — | 52s |
| 04-caregiver | 10/10 | 35 | 0 | — | 101s |
| 05-services | 35/35 | 78 | 0 | — | 221s |
| 06-prescription | 26/26 | 62 | 0 | — | 186s |
| 07-health-profile | 22/22 | 52 | 0 | — | 150s |
| 08-more | 17/17 | 43 | 0 | — | 154s |
| 09-ambulance-call | 10/10 | 29 | 0 | — | 146s |
| 10-admin-support | 9/9 | 35 | 0 | — | 181s |
| 11-notifications | 37/38 | 9 | 1 | — | 218s |
| 12-more-actions | 14/14 | 2 | 0 | — | 11s |

## ব্যর্থ দৃশ্য

- **11-notifications PUSH-web-doctor**: rx_review "রিপোর্ট দেখার টাকা মিলিয়ে নিন": খুলল /work/rx/reviews (আশা: /work/pro)

## ভুল ধাপ (status: bug)

- 11-notifications/03-step.png — ওয়েব: doctor-এর নোটিফিকেশন চাপলে ঠিক পাতা — এখানে আটকে গেছে — স্বয়ংক্রিয় ধাপ থেমেছে: rx_review "রিপোর্ট দেখার টাকা মিলিয়ে নিন": খুলল /work/rx/reviews (আশা: /work/pro)

## নোটিফিকেশন — ধরন, শিরোনাম, চাপলে কোথায় (Android / ওয়েব)

| যাচাই | ধরন | কার কাছে | শিরোনাম | Android | ওয়েবে খুলল |
|---|---|---|---|---|---|
| PUSH-request | request | e2e-push-donor | B- রক্ত দরকার | RequestDetail(BE5N9CXkwpEQXOq1f8Fr) | /request/BE5N9CXkwpEQXOq1f8Fr |
| PUSH-response | response | e2e-push-patient | একজন ডোনার রাজি হয়েছেন | MyRequestDetail(BE5N9CXkwpEQXOq1f8Fr) | /my-requests/BE5N9CXkwpEQXOq1f8Fr |
| PUSH-request-confirm | request | e2e-push-donor | আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন | RequestDetail(BE5N9CXkwpEQXOq1f8Fr) | /request/BE5N9CXkwpEQXOq1f8Fr |
| PUSH-request_removed | request_removed | e2e-push-donor | রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে | Main(HOME) | / |
| PUSH-emergency | emergency | e2e-push-donor | জরুরি: B- রক্ত দরকার | Main(HOME) + emergency banner | / |
| PUSH-care-invite | care | e2e-push-donor | ই২ই রোগী রুবেল আপনাকে কেয়ারগিভার করতে চান | Care | /care |
| PUSH-care-accepted | care | e2e-push-patient | ই২ই ডোনার দীপা এখন আপনার কেয়ারগিভার | Care | /care |
| PUSH-support | support | e2e-push-patient | Lifolo টিমের উত্তর এসেছে | Help | /help/kmif4xUhLZaMupQMCShw |
| PUSH-hospital | hospital | e2e-push-patient | আপনার চাওয়া হাসপাতাল তালিকায় যোগ হয়েছে | Main(HOME) | / |
| PUSH-badge | badge | e2e-push-patient | আপনার ব্যাজ-এর কাগজের ছবি দিন | Main(HOME) | /health/badge |
| PUSH-services-listing | services | ambulance | আপনার অ্যাম্বুলেন্স-এর কাগজের ছবি দিন | MyBusiness(ambulance) | /work/ambulance |
| PUSH-services-amb | services | ambulance | অ্যাম্বুলেন্স দরকার — প্রায় ২ কিমি দূরে | MyBusiness(ambulance) | /work/ambulance |
| PUSH-amb_req_mine | amb_req_mine | caregiver | ডেমো জীবন অ্যাম্বুলেন্স (এসি) ৳১১০০-তে আসতে রাজি | AmbulanceRequest(PxZPt5P0yo1XFEzhcpOg) | /services/ambulance/call/PxZPt5P0yo1XFEzhcpOg |
| PUSH-services-amb-agreed | services | ambulance | রোগী রাজি — ৳১১০০। এখনই রওনা দিন। | MyBusiness(ambulance) | /work/ambulance |
| PUSH-services-amb-cancel | services | ambulance | রোগী অ্যাম্বুলেন্সের অনুরোধ বাতিল করেছেন | MyBusiness(ambulance) | /work/ambulance |
| PUSH-answer | answer | patient | আপনার প্রশ্নের উত্তর এসেছে | QuestionDetail(5vmKAu8l5tR4cQSTbepl) | /health/question/5vmKAu8l5tR4cQSTbepl |
| PUSH-appointment-provider | appointment | doctor | নতুন অ্যাপয়েন্টমেন্টের অনুরোধ | ChamberEdit | /work/pro/bookings |
| PUSH-appointment-patient | appointment | donor | অ্যাপয়েন্টমেন্ট নেওয়া হয়নি | MyAppointments | /my-appointments |
| PUSH-pay_requested | pay_requested | patient | পেমেন্ট আপডেট — লাইফলো | Pay(PqAwZNBh7HnKUa3yBQrS) | /invoices/payments/PqAwZNBh7HnKUa3yBQrS |
| PUSH-invoice_new | invoice_new | patient | ডা. ডেমো হাসান থেকে নতুন রসিদ | Invoice(SCYwLmjjodcZc3SeZae8) | /invoices/SCYwLmjjodcZc3SeZae8 |
| PUSH-rx | rx | patient | ডা. ডেমো হাসান নতুন প্রেসক্রিপশন দিয়েছেন | Rx(Rp4cr2g1lIlHVeU9gJll) | /services/pharmacy/prescriptions/Rp4cr2g1lIlHVeU9gJll |
| PUSH-care-helper | care | caregiver | ডেমো রোগী রহিম: ডা. ডেমো হাসান নতুন প্রেসক্রিপশন দিয়েছেন | CareFor(demo-patient) › Rx(Rp4cr2g1lIlHVeU9gJll) | /services/pharmacy/prescriptions/Rp4cr2g1lIlHVeU9gJll |
| PUSH-rx_pharmacy | rx_pharmacy | pharmacy | নতুন অনলাইন অর্ডার | MyBusiness(pharmacy) › orders | /work/pharmacy/orders |
| PUSH-rx_lab | rx_lab | lab | নতুন পরীক্ষা — বাসা থেকে নমুনা | MyBusiness(lab) › orders | /work/lab/orders/A3Vpja9K4TbCoxac3e47 |
| PUSH-rx-home-confirm | rx | patient | ডেমো সিটি ডায়াগনস্টিক সেন্টার কাল সকাল ৮টায় আসবে | Rx(Rp4cr2g1lIlHVeU9gJll) | /services/pharmacy/prescriptions/Rp4cr2g1lIlHVeU9gJll |
| PUSH-rx-report | rx | patient | আপনার পরীক্ষার রিপোর্ট এসেছে | Rx(Rp4cr2g1lIlHVeU9gJll) | /services/pharmacy/prescriptions/Rp4cr2g1lIlHVeU9gJll |
| PUSH-rx_review | rx_review | doctor | রিপোর্ট দেখার টাকা মিলিয়ে নিন | RxReviews | /work/rx/reviews |
| PUSH-med_find | med_find | pharmacy | কাছে একজন ওষুধ খুঁজছেন | MyBusiness(pharmacy) › finds | /work/pharmacy/finds |
| PUSH-med_find_mine | med_find_mine | patient | ডেমো লাইফ ফার্মেসি-এ আছে | Main(SERVICES) › pharmacy › medicine finder | /services/pharmacy/medicine |
| PUSH-correction | correction | demo-x-farhana | তারিখ সংশোধনের অনুরোধ বাতিল হয়েছে | Main(HOME) | — |
| PUSH-eligible | eligible | e2e-push-donor | আপনি আবার রক্ত দিতে পারবেন | Main(HOME) | / |

## API-র ৪xx/৫xx (সংখ্যা)

- 64× 404 /api/community pay_request_get পাওয়া যায়নি
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
- 1× 400 /api/community device_register ফোন চেনা যায়নি
- 1× 404 /api/community question প্রশ্নটা পাওয়া যায়নি
- 1× 409 /api/community report_request আগেই রিপোর্ট করা হয়েছে
- 1× 400 /api/community phone_send_code ফোন যাচাই এখনো চালু হয়নি
- 1× 400 /api/community phone_verify_code কোডের মেয়াদ শেষ। নতুন কোড নিন।
- 1× 401 /api/community admin_overview Please log in
- 1× 404 /api/auth/verify-code undefined ডেমো চালু নেই
