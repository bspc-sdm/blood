# Web e2e — spring ✅

যাত্রা 9 (থেমেছে 0) · দৃশ্য 175/175 পাস (0 ব্যর্থ) · ধাপ 442 (ভুল 0) · API 5xx 0 · সার্ভার ERROR 0 · নিজে ধরা সন্দেহ 4

| যাত্রা | দৃশ্য পাস | ধাপ | ভুল ধাপ | থেমেছে | সময় |
|---|---|---|---|---|---|
| 00-demo-seed | 0/0 | 4 | 0 | — | 8s |
| 01-core-journey | 29/29 | 84 | 0 | — | 123s |
| 02-blood | 30/30 | 77 | 0 | — | 121s |
| 03-ambulance | 7/7 | 17 | 0 | — | 38s |
| 04-caregiver | 10/10 | 34 | 0 | — | 48s |
| 05-services | 35/35 | 75 | 0 | — | 115s |
| 06-prescription | 26/26 | 60 | 0 | — | 87s |
| 07-health-profile | 22/22 | 52 | 0 | — | 104s |
| 08-more | 16/16 | 39 | 0 | — | 79s |

## API-র ৪xx/৫xx (সংখ্যা)

- 7× 401 /api/community admin_overview লগইন দরকার
- 2× 409 /api/community book সেদিন এই ডাক্তারের কাছে আপনার অনুরোধ আগে থেকেই আছে
- 1× 403 /api/community blood_responses_list এটা আপনার রিকোয়েস্ট না
- 1× 409 /api/community rx_send_lab এই প্যাথলজিতে আগেই পাঠানো আছে
- 1× 400 /api/community review_settings টাকা পাঠানোর মোবাইল নম্বর দিন
- 1× 400 /api/community rx_team_set নিজেকে সহকারী হিসেবে যোগ করা যায় না
- 1× 400 /api/community rx_claim প্রোফাইলের বা পরিবারের সদস্যের মোবাইল নম্বরটাই দিন
- 1× 409 /api/community lab_open_code এই প্রেসক্রিপশন বাতিল
