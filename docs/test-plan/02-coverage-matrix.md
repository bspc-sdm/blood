# ২. কভারেজ ম্যাট্রিক্স — কোন জিনিস কোন স্বয়ংক্রিয় টেস্টে আছে

> এই ফাইল কোড থেকে বানানো: `python3 docs/test-plan/tools/build_inventory.py` (হাতে বদলাবেন না — RC-র কোডে আবার চালান)।


সংকেত: **BE** = Spring টেস্ট (`backend/src/test`, unit + IT, ফাইলের নাম), **WEB-J:n** = ৯টা ওয়েব যাত্রা চালানোর সময় এই action কতবার ডাকা হয়েছে (`tools/data/web-journeys-actions.json`, লোকাল স্ট্যাক; যাত্রাগুলো **পুরোনো React ওয়েব** আর Node সার্ভারে চলেছে — নাম একই, তাই action-কভারেজ খাটে, কিন্তু Vue ওয়েবের UI কভারেজ নয়), **VUE-mock** = Vue Playwright নকল API-র fixture-এ আছে (শুধু UI দেখায়, সার্ভার নয়), **Robo** = Android Robolectric স্ক্রিনশট টেস্ট (শুধু হাতে চালানো workflow), **Maestro:jN** = Android Maestro যাত্রা (প্রোডাকশন ডেমোতে, হাতে/সাপ্তাহিক)। **NONE** = কোনো স্বয়ংক্রিয় টেস্ট নেই।

## সংখ্যা

- action: 225 — Spring টেস্টে নাম আছে 225, ওয়েব যাত্রায় ডাকা 158, কোনোটাতেই নেই 0; **UI-র পুরো পথে (e2e) নেই 67**, Spring-এ পাতলা আর e2e-তেও নেই 17
- Android স্ক্রিন/ডায়ালগ/শিট/ট্যাব: 139 — Robolectric বা Maestro-তে নেই **76**
- Vue কম্পোনেন্ট ফাইল: 365 — যাত্রার লেখায় মেলেনি 30 (Vue-র নিজের Playwright নকল-API টেস্ট 219টা পাতা দেখে, কিন্তু আসল সার্ভারে নয়)
- পুশ: সার্ভারে পুশ লেখা হয় কি না কয়েকটা IT দেখে (`RecordingPushTransport`); **ফোনে আসা আর চাপলে ঠিক পাতা খোলা — কোনো স্বয়ংক্রিয় টেস্ট নেই**
- ইমেইল: `AuthIT` নকল মেইলে কোড দেখে; **আসল ইনবক্সে পৌঁছানো (SES, Spam) — নেই**
- ক্রন: DailyTask-গুলোর IT আছে কিছুর (নিচে); **প্রোডাকশনে 03:00 UTC-তে সত্যি চলল কি না — নজরদারি নেই**

## ক) action কভারেজ

| action | package | কভারেজ |
|---|---|---|
| `admin_add` | admin | BE×5:AdminPanelIT,CommonUnitTest · VUE-mock |
| `admin_catalog_set` | admin | BE×5:AdminPanelIT,CommonUnitTest |
| `admin_list` | admin | BE×3:AdminPanelIT |
| `admin_log` | admin | BE×3:AdminPanelIT · WEB-J:16 · VUE-mock |
| `admin_overview` | admin | BE×8:AdminPanelIT,AuthIT,CommunityDispatchIT… · WEB-J:91 · VUE-mock |
| `admin_remove` | admin | BE×1:AdminPanelIT thin · VUE-mock |
| `admin_report_resolve` | admin | BE×10:DemoIT,DemoServicesIT,ModerationIT · WEB-J:3 · VUE-mock |
| `admin_reports` | admin | BE×10:AdminPanelIT,DemoIT,DemoServicesIT… |
| `admin_request_docs` | admin | BE×4:AdminPanelIT · VUE-mock |
| `admin_stats` | admin | BE×4:AdminPanelIT · WEB-J:15 · VUE-mock |
| `admin_ticket` | admin | BE×2:SupportIT thin · WEB-J:3 · VUE-mock |
| `admin_ticket_reply` | admin | BE×5:CommonUnitTest,DemoIT,SupportIT · WEB-J:1 · VUE-mock |
| `admin_tickets` | admin | BE×4:DemoIT,SupportIT · VUE-mock |
| `admin_user` | admin | BE×2:AdminPanelIT thin · WEB-J:2 · VUE-mock |
| `admin_user_action` | admin | BE×12:AdminPanelIT,CommonUnitTest · VUE-mock |
| `admin_user_find` | admin | BE×5:AdminPanelIT · WEB-J:1 · VUE-mock |
| `admin_user_section` | admin | BE×6:AdminPanelIT,DemoIT · WEB-J:2 · VUE-mock |
| `admin_verify_docs` | admin | BE×5:AdminPanelIT,DemoIT,DemoServicesIT… · WEB-J:8 · VUE-mock |
| `catalog_get` | admin | BE×5:AdminPanelIT,CommunityDispatchIT · WEB-J:45 · VUE-mock |
| `report` | admin | BE×53:AppointmentsIT,CommunityDispatchIT,DemoIT… · WEB-J:1 |
| `ticket_create` | admin | BE×10:AdminPanelIT,DemoIT,SupportIT |
| `ticket_reply` | admin | BE×3:SupportIT |
| `tickets_mine` | admin | BE×5:DemoIT,SupportIT · WEB-J:2 |
| `verify_docs_upload` | admin | BE×4:AdminPanelIT,R2StorageIT |
| `admin_ambulance_resolve` | ambulance | BE×4:AmbulanceDemoIT,CommonUnitTest,DemoServicesIT · WEB-J:1 · VUE-mock |
| `admin_ambulances` | ambulance | BE×4:AmbFareIT,AmbulanceDemoIT,DemoServicesIT · WEB-J:1 · VUE-mock |
| `amb_facilities` | ambulance | BE×6:AmbPlaceIT |
| `ambreq_accept` | ambulance | BE×53:AmbFareIT,AmbReqIT,AmbulanceDemoIT |
| `ambreq_agree` | ambulance | BE×12:AmbFareIT,AmbulanceDemoIT |
| `ambreq_budget` | ambulance | BE×6:AmbFareIT |
| `ambreq_byphone` | ambulance | BE×6:AmbFareIT,AmbReqIT |
| `ambreq_cancel` | ambulance | BE×16:AmbFareIT,AmbReqIT |
| `ambreq_count` | ambulance | BE×7:AmbFareIT,AmbPlaceIT,AmbulanceDemoIT |
| `ambreq_create` | ambulance | BE×11:AmbFareIT,AmbPlaceIT,AmbReqIT… |
| `ambreq_decline` | ambulance | BE×2:AmbReqIT thin |
| `ambreq_driver` | ambulance | BE×5:AmbPlaceIT,AmbReqIT,AmbTestBase · WEB-J:9 |
| `ambreq_replace` | ambulance | BE×3:AmbFareIT,AmbReqIT |
| `ambreq_status` | ambulance | BE×23:AmbFareIT,AmbReqIT,AmbulanceDemoIT · WEB-J:7 |
| `ambreq_step` | ambulance | BE×9:AmbFareIT,AmbReqIT,AmbulanceDemoIT |
| `ambulance_available` | ambulance | BE×8:AmbReqIT,AmbulanceDemoIT,AmbulanceNearIT… · WEB-J:2 |
| `ambulance_called` | ambulance | BE×17:AmbFareIT,AmbReqIT,AmbulanceDemoIT… · WEB-J:1 |
| `ambulance_delete` | ambulance | BE×5:AmbulanceNearIT |
| `ambulance_mine` | ambulance | BE×10:AmbFareIT,AmbulanceDemoIT,AmbulanceNearIT… · WEB-J:391 |
| `ambulance_rate` | ambulance | BE×10:AmbFareIT,AmbReqIT,AmbulanceDemoIT… · WEB-J:1 |
| `ambulance_save` | ambulance | BE×13:AmbFareIT,AmbulanceDemoIT,AmbulanceNearIT · WEB-J:2 |
| `ambulance_stats` | ambulance | BE×3:AmbReqIT,AmbulanceDemoIT,DemoServicesIT · WEB-J:8 |
| `ambulances` | ambulance | BE×30:AdminPanelIT,AmbReqIT,AmbulanceDemoIT… · WEB-J:13 · VUE-mock |
| `maps_link_resolve` | ambulance | BE×8:AmbPlaceIT |
| `appointment_late` | appointments | BE×8:QueueIT · WEB-J:1 |
| `appointment_late_reply` | appointments | BE×4:QueueIT · WEB-J:2 |
| `appointment_queue` | appointments | BE×18:QueueIT · WEB-J:15 |
| `appointment_update` | appointments | BE×15:AppointmentsIT,DemoIT,DemoRxIT… · WEB-J:4 |
| `book` | appointments | BE×27:AppointmentsIT,CommunityDispatchIT,DemoIT… · WEB-J:6 · VUE-mock |
| `my_appointments` | appointments | BE×15:AppointmentsIT,CareIT,DemoIT… · WEB-J:34 |
| `provider_me` | appointments | BE×7:AppointmentsIT,DemoServicesIT · WEB-J:15 |
| `provider_save` | appointments | BE×15:AppointmentsIT · WEB-J:2 |
| `providers` | appointments | BE×12:AppointmentsIT,DemoIT,DemoRxIT… · WEB-J:11 |
| `queue_control` | appointments | BE×28:QueueIT · WEB-J:8 |
| `queue_today` | appointments | BE×6:QueueIT · WEB-J:28 |
| `web_logout` | auth | BE×1:AuthIT thin |
| `admin_bloodbank_add` | blood | BE×2:BloodBankIT thin · VUE-mock |
| `admin_bloodbank_delete` | blood | BE×2:BloodBankIT thin · VUE-mock |
| `admin_bloodbank_edit` | blood | BE×3:BloodBankIT · VUE-mock |
| `admin_bloodbank_resolve` | blood | BE×4:BloodBankIT,BloodDemoSeedIT · WEB-J:1 · VUE-mock |
| `admin_bloodbanks` | blood | BE×3:BloodBankIT,BloodDemoSeedIT · VUE-mock |
| `admin_districts` | blood | BE×1:BloodBankIT thin · WEB-J:4 · VUE-mock |
| `admin_emergencies` | blood | BE×3:BloodDemoSeedIT,EmergencyIT · WEB-J:2 · VUE-mock |
| `admin_emergency_close` | blood | BE×2:EmergencyIT thin · WEB-J:1 · VUE-mock |
| `admin_emergency_create` | blood | BE×3:EmergencyIT |
| `admin_emergency_group` | blood | BE×3:EmergencyIT **neg-only** · VUE-mock |
| `admin_emergency_more` | blood | BE×1:EmergencyIT thin · VUE-mock |
| `blood_contact_get` | blood | BE×8:BloodDemoSeedIT,BloodFlowIT,BloodRequestIT · WEB-J:5 |
| `blood_request_get` | blood | BE×7:BloodRequestIT · WEB-J:38 |
| `blood_requests_mine` | blood | BE×3:BloodRequestIT · WEB-J:83 |
| `blood_requests_near` | blood | BE×9:BloodDemoSeedIT,BloodFlowIT,BloodRequestIT · WEB-J:77 |
| `blood_response_get` | blood | BE×6:BloodDemoSeedIT,BloodRequestIT · WEB-J:32 |
| `blood_responses_list` | blood | BE×4:BloodRequestIT · WEB-J:17 |
| `bloodbank_called` | blood | BE×6:BloodBankIT,BloodDemoSeedIT,BloodRequestIT |
| `bloodbank_delete` | blood | BE×4:BloodBankIT **neg-only** |
| `bloodbank_mine` | blood | BE×3:BloodBankIT,BloodDemoSeedIT · WEB-J:391 |
| `bloodbank_save` | blood | BE×16:BloodBankIT,BloodDemoSeedIT · WEB-J:1 |
| `bloodbank_stats` | blood | BE×1:BloodBankIT thin · WEB-J:2 |
| `bloodbanks` | blood | BE×7:BloodBankIT,BloodDemoSeedIT · WEB-J:3 · VUE-mock |
| `confirm_donor` | blood | BE×9:BloodDemoSeedIT,BloodFlowIT,ShareIT · WEB-J:1 |
| `donation_date_set` | blood | BE×12:BloodDemoSeedIT,BloodFlowIT · WEB-J:1 |
| `emergencies_active` | blood | BE×12:BloodDemoSeedIT,BloodRequestIT,EmergencyIT · WEB-J:90 |
| `emergency_donated` | blood | BE×5:BloodDemoSeedIT,EmergencyIT **neg-only** · WEB-J:1 |
| `emergency_respond` | blood | BE×15:BloodDemoSeedIT,BloodRequestIT,EmergencyIT · WEB-J:2 |
| `guest_requests` | blood | BE×5:BloodDemoSeedIT,BloodRequestIT,CommunityDispatchIT · WEB-J:3 |
| `report_request` | blood | BE×6:BloodRequestIT |
| `request_create` | blood | BE×13:BloodDemoSeedIT,BloodRequestIT,CommunityDispatchIT… · WEB-J:1 |
| `request_delete` | blood | BE×4:BloodFlowIT · WEB-J:1 |
| `request_reopen` | blood | BE×4:BloodFlowIT |
| `response_update` | blood | BE×11:BloodDemoSeedIT,BloodFlowIT,BloodRequestIT · WEB-J:3 |
| `books_adjust` | books | BE×11:ShopBooksIT · WEB-J:2 |
| `books_adjusts` | books | BE×3:ShopBooksIT |
| `books_item_delete` | books | BE×3:ShopBooksIT |
| `books_item_save` | books | BE×52:PharmacyListIT,RxPharmacyIT,ShopBooksIT · WEB-J:4 |
| `books_item_stock` | books | BE×4:ShopBooksIT |
| `books_items` | books | BE×13:DemoBooksIT,ShopBooksIT · WEB-J:14 |
| `books_purchase_add` | books | BE×11:ShopBooksIT · WEB-J:1 |
| `books_purchase_delete` | books | BE×6:ShopBooksIT · WEB-J:1 |
| `books_purchases` | books | BE×2:ShopBooksIT thin · WEB-J:3 |
| `books_report` | books | BE×8:DemoBooksIT,ShopBooksIT · WEB-J:4 |
| `books_rx_preview` | books | BE×5:ShopBooksIT · WEB-J:3 |
| `books_sale_add` | books | BE×24:ShopBooksIT · WEB-J:3 |
| `books_sale_delete` | books | BE×4:ShopBooksIT · WEB-J:1 |
| `books_sales` | books | BE×8:DemoBooksIT,ShopBooksIT · WEB-J:6 |
| `books_today` | books | BE×3:DemoBooksIT,ShopBooksIT |
| `care_invite` | care | BE×14:CareIT,DemoIT · WEB-J:1 |
| `care_list` | care | BE×9:CareIT,DemoIT · WEB-J:15 |
| `care_remove` | care | BE×7:CareIT,DemoIT · WEB-J:1 |
| `care_respond` | care | BE×4:CareIT,DemoIT · WEB-J:1 |
| `family_get` | care | BE×7:CareIT,DemoIT · WEB-J:3 |
| `family_set` | care | BE×11:AppointmentsIT,CareIT,DemoIT · WEB-J:2 |
| `admin_badge_resolve` | content | BE×19:AppointmentsIT,CommonUnitTest,CommunityIT… · WEB-J:6 · VUE-mock |
| `admin_badges` | content | BE×10:CommunityIT,DemoIT,DemoRxIT… · WEB-J:4 · VUE-mock |
| `admin_hospital_requests` | content | BE×2:HospitalsIT thin |
| `admin_hospital_resolve` | content | BE×6:HospitalsIT · WEB-J:1 · VUE-mock |
| `admin_hospitals` | content | BE×2:HospitalsIT thin · VUE-mock |
| `answer` | content | BE×22:CommunityDispatchIT,CommunityIT,DemoIT… · WEB-J:1 · VUE-mock |
| `ask` | content | BE×19:BloodDemoSeedIT,BloodRequestIT,CareIT… · WEB-J:1 · VUE-mock |
| `badge_apply` | content | BE×11:CareIT,CommunityIT,DemoIT · WEB-J:1 |
| `delete_answer` | content | BE×3:CommunityIT |
| `delete_question` | content | BE×1:CommunityIT thin |
| `delete_tip` | content | BE×2:CommunityIT thin |
| `edit_answer` | content | BE×3:CommunityIT |
| `edit_question` | content | BE×3:CommunityIT · WEB-J:1 |
| `edit_tip` | content | BE×3:CommunityIT |
| `hospital_request` | content | BE×11:HospitalsIT |
| `hospitals` | content | BE×6:AmbFareIT,AmbulanceDemoIT,HospitalsIT · WEB-J:2 · VUE-mock |
| `me` | content | BE×24:AdminPanelIT,AppointmentsIT,AuthIT… · WEB-J:401 · VUE-mock |
| `post_tip` | content | BE×6:CommunityIT,DemoIT · WEB-J:1 |
| `question` | content | BE×20:CommunityIT,DemoIT,ModerationIT · WEB-J:6 · VUE-mock |
| `questions` | content | BE×10:CommunityIT,DemoIT · WEB-J:12 · VUE-mock |
| `tips` | content | BE×6:CommunityIT,DemoIT · WEB-J:5 · VUE-mock |
| `admin_demo_seed` | demo | BE×7:DemoIT,DemoTestBase,R2StorageIT · WEB-J:6 · VUE-mock |
| `admin_demo_status` | demo | BE×3:DemoIT · WEB-J:2 · VUE-mock |
| `admin_demo_wipe` | demo | BE×8:DemoIT,DemoRxIT,DemoServicesIT… |
| `home_called` | homecare | BE×6:DemoServicesIT,HomecareIT |
| `home_providers` | homecare | BE×12:AppointmentsIT,DemoServicesIT,HomecareIT · WEB-J:3 |
| `home_rate` | homecare | BE×8:DemoServicesIT,HomecareIT |
| `home_stats` | homecare | BE×4:DemoServicesIT,HomecareIT · WEB-J:4 |
| `admin_lab_resolve` | lab | BE×8:DemoRxIT,DemoServicesIT,LabListingIT · WEB-J:1 · VUE-mock |
| `admin_labs` | lab | BE×6:DemoRxIT,DemoServicesIT,LabListingIT · WEB-J:1 · VUE-mock |
| `admin_labtests_set` | lab | BE×3:RxFlowIT |
| `lab_delete` | lab | BE×5:LabListingIT,LabPricesIT,R2StorageIT |
| `lab_form` | lab | BE×5:DemoRxIT,RxFlowIT · WEB-J:6 |
| `lab_home_cancel` | lab | BE×4:LabPricesIT |
| `lab_home_change` | lab | BE×5:LabPricesIT · WEB-J:1 |
| `lab_home_confirm` | lab | BE×13:DemoRxIT,LabPricesIT · WEB-J:2 |
| `lab_home_decline` | lab | BE×4:LabListingIT,LabPricesIT · WEB-J:1 |
| `lab_me` | lab | BE×8:DemoServicesIT,LabListingIT · WEB-J:391 |
| `lab_open_code` | lab | BE×6:DemoRxIT,LabPricesIT · WEB-J:2 |
| `lab_order_get` | lab | BE×30:DemoIT,DemoRxIT,LabListingIT… · WEB-J:8 |
| `lab_order_status` | lab | BE×15:DemoRxIT,LabPricesIT,RxFlowIT · WEB-J:2 |
| `lab_orders` | lab | BE×21:DemoIT,DemoRxIT,DemoServicesIT… · WEB-J:22 |
| `lab_prices_get` | lab | BE×4:DemoRxIT,LabPricesIT · WEB-J:5 |
| `lab_prices_public` | lab | BE×10:DemoRxIT,LabPricesIT · WEB-J:1 |
| `lab_prices_set` | lab | BE×25:LabPricesIT · WEB-J:2 |
| `lab_ranges_set` | lab | BE×2:DemoServicesIT,RxFlowIT thin |
| `lab_report` | lab | BE×7:DemoRxIT,LabPricesIT,R2StorageIT… · WEB-J:2 |
| `lab_save` | lab | BE×10:LabListingIT,R2StorageIT |
| `labs` | lab | BE×17:AdminPanelIT,DemoIT,DemoRxIT… · WEB-J:2 · VUE-mock |
| `labtests_get` | lab | BE×1:RxFlowIT thin · WEB-J:5 · VUE-mock |
| `review_confirm` | lab | BE×3:DemoRxIT,RxFlowIT · WEB-J:2 |
| `review_done` | lab | BE×5:DemoRxIT,RxFlowIT · WEB-J:2 |
| `review_pay` | lab | BE×4:DemoRxIT,RxFlowIT · WEB-J:1 |
| `review_settings` | lab | BE×6:RxFlowIT · WEB-J:6 |
| `reviews_doctor` | lab | BE×5:DemoRxIT,RxFlowIT · WEB-J:9 |
| `rx_lab_options` | lab | BE×17:DemoRxIT,LabPricesIT · WEB-J:5 |
| `rx_send_lab` | lab | BE×23:DemoIT,DemoRxIT,LabPricesIT… · WEB-J:4 |
| `device_register` | notify | BE×4:InboxDevicesIT |
| `device_unregister` | notify | BE×2:InboxDevicesIT thin |
| `inbox` | notify | BE×14:AdminPanelIT,DemoIT,InboxDevicesIT… · WEB-J:10 · VUE-mock |
| `inbox_read` | notify | BE×2:DemoIT,InboxDevicesIT thin · WEB-J:9 |
| `admin_pharmacies` | pharmacy | BE×5:DemoServicesIT,PharmacyListIT · WEB-J:1 · VUE-mock |
| `admin_pharmacy_resolve` | pharmacy | BE×9:CommonUnitTest,DemoServicesIT,PharmacyListIT · WEB-J:1 · VUE-mock |
| `drug_search` | pharmacy | BE×7:DemoServicesIT,MedFindIT,PharmacyListIT · WEB-J:6 |
| `med_find` | pharmacy | BE×18:CommunityDispatchIT,DemoIT,DemoServicesIT… · WEB-J:1 |
| `med_find_answer` | pharmacy | BE×9:DemoServicesIT,MedFindIT · WEB-J:2 |
| `med_find_close` | pharmacy | BE×2:MedFindIT thin |
| `med_find_requests` | pharmacy | BE×8:DemoServicesIT,MedFindIT · WEB-J:23 |
| `med_finds_mine` | pharmacy | BE×3:DemoServicesIT,MedFindIT · WEB-J:3 |
| `pharmacies` | pharmacy | BE×52:AdminPanelIT,DeletionIT,DemoServicesIT… · WEB-J:9 · VUE-mock |
| `pharmacy_called` | pharmacy | BE×3:DemoIT,DemoServicesIT,PharmacyRateIT |
| `pharmacy_delete` | pharmacy | BE×6:PharmacyListIT,PharmacyRateIT |
| `pharmacy_find_setting` | pharmacy | BE×3:MedFindIT,PharmacyListIT |
| `pharmacy_me` | pharmacy | BE×13:DemoServicesIT,MedFindIT,PharmacyListIT · WEB-J:391 |
| `pharmacy_pause` | pharmacy | BE×5:PharmacyListIT · WEB-J:2 |
| `pharmacy_rate` | pharmacy | BE×26:DemoServicesIT,PharmacyRateIT · WEB-J:1 |
| `pharmacy_save` | pharmacy | BE×20:DemoServicesIT,PharmacyListIT · WEB-J:1 |
| `pharmacy_stats` | pharmacy | BE×4:DemoServicesIT,PharmacyRateIT · WEB-J:7 |
| `rx_dispense` | pharmacy | BE×17:DemoRxIT,DemoServicesIT,RxPharmacyIT… · WEB-J:3 |
| `rx_missed_clear` | pharmacy | BE×3:RxPharmacyIT · WEB-J:1 |
| `rx_order_update` | pharmacy | BE×12:DemoRxIT,DemoServicesIT,RxPharmacyIT · WEB-J:6 |
| `rx_order_withdraw` | pharmacy | BE×6:DemoIT,RxPharmacyIT · WEB-J:1 |
| `rx_orders_pharmacy` | pharmacy | BE×11:DemoRxIT,DemoServicesIT,RxPharmacyIT · WEB-J:27 |
| `rx_pharmacy_home` | pharmacy | BE×10:DemoRxIT,DemoServicesIT,RxPharmacyIT · WEB-J:4 |
| `rx_pharmacy_online_set` | pharmacy | BE×1:RxPharmacyIT thin |
| `rx_pharmacy_open` | pharmacy | BE×7:DemoRxIT,DemoServicesIT,RxPharmacyIT · WEB-J:6 |
| `rx_pharmacy_options` | pharmacy | BE×22:DemoIT,DemoRxIT,DemoServicesIT… · WEB-J:5 |
| `rx_send_pharmacy` | pharmacy | BE×14:DemoIT,DemoRxIT,DemoServicesIT… · WEB-J:4 |
| `rx_shop_find` | pharmacy | BE×4:DemoServicesIT,RxPharmacyIT |
| `stock_get` | pharmacy | BE×5:DemoServicesIT,RxPharmacyIT |
| `stock_set` | pharmacy | BE×13:DemoBooksIT,DemoServicesIT,PharmacyListIT… · WEB-J:1 |
| `rx_activity` | rx | BE×14:DemoIT,DemoRxIT,DemoServicesIT… · WEB-J:32 |
| `rx_assisting` | rx | BE×5:DemoRxIT,RxFlowIT · WEB-J:480 |
| `rx_cancel` | rx | BE×2:RxFlowIT thin · WEB-J:1 |
| `rx_claim` | rx | BE×11:DemoRxIT,LabPricesIT,RxFlowIT · WEB-J:2 |
| `rx_create` | rx | BE×20:DemoRxIT,LabPricesIT,RxFlowIT · WEB-J:1 |
| `rx_doctor_list` | rx | BE×3:DemoRxIT,RxFlowIT · WEB-J:1 |
| `rx_get` | rx | BE×9:DemoIT,RxFlowIT · WEB-J:4 |
| `rx_me` | rx | BE×2:DemoRxIT,RxFlowIT thin · WEB-J:9 |
| `rx_mine` | rx | BE×19:CommunityDispatchIT,DemoIT,DemoRxIT… · WEB-J:31 |
| `rx_patient_add` | rx | BE×9:DemoRxIT,LabPricesIT,RxFlowIT · WEB-J:2 |
| `rx_patient_history` | rx | BE×16:DemoRxIT,LabPricesIT,RxFlowIT · WEB-J:3 |
| `rx_patients` | rx | BE×11:DemoRxIT,RxFlowIT · WEB-J:18 |
| `rx_team` | rx | BE×1:RxFlowIT thin · WEB-J:1 |
| `rx_team_set` | rx | BE×6:DemoRxIT,RxFlowIT · WEB-J:1 (only errors) |
| `delete_account` | users | BE×9:DeletionIT,DemoIT,R2StorageIT · WEB-J:1 |
| `phone_send_code` | users | BE×2:InboxDevicesIT thin |
| `phone_verify_code` | users | BE×5:InboxDevicesIT |
| `profile_get` | users | BE×27:AuthIT,CommunityDispatchIT,ProfileIT… · WEB-J:302 |
| `profile_update` | users | BE×32:ProfileIT,R2StorageIT,StorageOffIT · WEB-J:312 |

### NONE — কোনো স্বয়ংক্রিয় টেস্টে নেই এমন action

০টা — phase 2-এর পোর্টে প্রতিটা action-এর অন্তত একটা Spring IT/unit আছে। আসল ফাঁক নিচের দুটো তালিকায়।


### ফাঁক ১ — কোনো end-to-end (অ্যাপ/ওয়েবের UI দিয়ে) টেস্টে নেই, শুধু Spring IT

67টা। UI থেকে সার্ভার পর্যন্ত পুরো পথ কেউ চালায় না — এগুলো হাতে টেস্টে (04) আর Phase 2-এর নতুন যাত্রায়।

| # | action | কী করে | Spring টেস্ট | কেন জরুরি |
|---|---|---|---|---|
| 1 | `admin_add` | Add admin email (≤20); clears admin cache | ×5 | অ্যাডমিন বানানো — নিরাপত্তা |
| 2 | `admin_remove` | Remove admin email; clears admin cache | ×1 | অ্যাডমিন সরানো — নিরাপত্তা |
| 3 | `admin_reports` | Open reports grouped by target (provider reports only with panel:true) | ×10 | রিপোর্টের তালিকা |
| 4 | `admin_request_docs` | Ask owner for document photos (note); pushes owner | ×4 | কাগজ চাওয়া |
| 5 | `admin_tickets` | Tickets by status (open default) | ×4 | আপিলের তালিকা |
| 6 | `admin_user_action` | Disable/enable account (Auth + revoke + disabledReason), lift/impose sanctions, home-care block; pus | ×12 | অ্যাকাউন্ট বন্ধ/চালু, নিষেধাজ্ঞা — ভুল হলে নিরীহ মানুষ বন্ধ |
| 7 | `ticket_create` | Open support/appeal ticket (category, text); quota ticket=5/day | ×10 | আপিল/সাপোর্ট — একমাত্র যোগাযোগের পথ |
| 8 | `verify_docs_upload` | Owner uploads requested document photos (base64) for badge/pharmacy/ambulance/bloodbank/lab; quota d | ×4 | কাগজের ছবি — যাচাই |
| 9 | `ambreq_accept` | "Coming": quote mode stores fare offer (≤ budget, 2 min validity) else first-come accept; marks ambu | ×53 | চালক রাজি — জীবন-মরণ |
| 10 | `ambreq_agree` | Patient accepts a quote → accepted, agreedFare; releases other quotes, pushes losers | ×12 | রোগী ভাড়ায় রাজি |
| 11 | `ambreq_budget` | Raise max fare / includeUnknown; sends to ≤5 newly matching ambulances, resets 3-min expiry | ×6 | ভাড়া বাড়ানো |
| 12 | `ambreq_byphone` | Patient found a car by phone (one they called during this request) → close as byPhone, push others | ×6 | ফোনে পাওয়া গাড়ি |
| 13 | `ambreq_cancel` | Patient cancels with reason; frees ambulance, pushes driver(s) | ×16 | রোগী বাতিল — চালক মুক্ত |
| 14 | `ambreq_count` | Preview count of matching free ambulances for the fare/type/destination (no writes; district list ca | ×7 | ভাড়ার মধ্যে কয়টা গাড়ি |
| 15 | `ambreq_create` | "Call an ambulance": pick candidates (≤130 km, available, type, fare filter), wave of 5, push driver | ×11 | অ্যাম্বুলেন্স ডাকা — জীবন-মরণ |
| 16 | `ambreq_decline` | Driver declines; widens early if everyone declined | ×2 | চালক না — পরের ঢেউ |
| 17 | `ambreq_replace` | Drop accepted driver (patient_found_other); with ambulanceId → byPhone close, else reopen without th | ×3 | চালক বদল |
| 18 | `ambreq_step` | Driver: arrived / done / cancel(reason); records trip on ambulanceCalls (agreedFare) for rating | ×9 | পৌঁছেছি/শেষ |
| 19 | `ambulance_delete` | Delete own ambulance (not when suspended or with open reports); deletes verify docs | ×5 | অ্যাম্বুলেন্স মোছা |
| 20 | `admin_emergency_create` | Create emergency call (groups/need, radius, hours, hospital, contact) and send first wave of pushes | ×3 | জরুরি রক্তের আহ্বান — পুশের ঢেউ |
| 21 | `admin_emergency_group` | Close/reopen one blood group | ×3 neg-only | একটা গ্রুপ বন্ধ |
| 22 | `admin_emergency_more` | Send next wave to not-yet-notified eligible donors | ×1 | জরুরি আহ্বানের পরের ঢেউ |
| 23 | `request_reopen` | Reopen within 1 h of closing (undo close marks from meta/close, restore donation dates); pushes dono | ×4 | ভুল করে বন্ধ — আবার খোলা |
| 24 | `lab_home_cancel` | Patient cancels home collection before confirmation (removes address/phone); pushes lab | ×4 | বাসা থেকে নমুনা বাতিল — ঠিকানা মোছা |
| 25 | `lab_save` | Register/edit lab (DGHS facilityId or own name, license, hours, homeCollection, docs); re-verify; qu | ×10 | প্যাথলজি নিবন্ধন |
| 26 | `device_register` | Attach FCM token to this account (≤5, removed from other accounts) | ×4 | পুশ টোকেন — না চললে কোনো নোটিফিকেশন যায় না |
| 27 | `device_unregister` | Remove this FCM token from account | ×2 | লগআউটের পর অন্যের ফোনে পুশ যাওয়া ঠেকায় |
| 28 | `pharmacy_delete` | Remove own pharmacy (not suspended); deletes stats, ratings, docs, strips order contacts | ×6 | দোকান মোছা — অর্ডারের ঠিকানা মোছা |
| 29 | `phone_send_code` | SMS code to profile phone (SMS_URL_TEMPLATE; disabled when unset → 400); per-number 3/day + 1/min (p | ×2 | SMS — এখন বন্ধ (SMS_URL_TEMPLATE নেই) |
| 30 | `phone_verify_code` | Verify 6-digit SMS code (5 tries, 10 min) → verifiedPhone | ×5 | SMS যাচাই |
| 31 | `admin_catalog_set` | Save one pick-list (order, names, hidden, new codes ≤80) | ×5 |  |
| 32 | `admin_list` | List owners (env) and extra admins (config/admins) | ×3 |  |
| 33 | `ticket_reply` | Add message (reopens closed ticket) | ×3 |  |
| 34 | `amb_facilities` | Destination hospital list for district (cached 10 min) or search q / nearest to lat,lng | ×6 |  |
| 35 | `maps_link_resolve` | Google Maps link/text → {lat,lng}; short links resolved by HTTP redirect (per-uid in-memory limit, 4 | ×8 |  |
| 36 | `web_logout` | Clears the admin web-session cookie | ×1 |  |
| 37 | `admin_bloodbank_add` | Admin adds an ownerless approved blood bank | ×2 |  |
| 38 | `admin_bloodbank_delete` | Admin deletes blood bank (+stats, docs, reports); pushes owner with reason | ×2 |  |
| 39 | `admin_bloodbank_edit` | Admin edits any blood bank; pushes owner if any | ×3 |  |
| 40 | `admin_bloodbanks` | Blood banks by status | ×3 |  |
| 41 | `bloodbank_called` | Record call (10-min dedupe) + bloodBankStats | ×6 |  |
| 42 | `bloodbank_delete` | Delete own blood bank (not suspended / open reports) + stats + verify docs | ×4 neg-only |  |
| 43 | `report_request` | Report a blood request once (reports/{requestId}_{uid}); 409 if repeated | ×6 |  |
| 44 | `books_adjusts` | Adjustments of last 90 days | ×3 |  |
| 45 | `books_item_delete` | Delete product (history kept); syncs stock list | ×3 |  |
| 46 | `books_item_stock` | Manual in-stock on/off for non-quantity shops | ×4 |  |
| 47 | `books_today` | Today card: sales, net, profit, write-off, low stock, expiring in 60 days, priced flag | ×3 |  |
| 48 | `admin_hospital_requests` | Pending hospital requests with possible duplicates | ×2 |  |
| 49 | `admin_hospitals` | Hospitals of district for emergency form | ×2 |  |
| 50 | `delete_answer` | Delete answer, decrement answerCount; admin deletion logged | ×3 |  |
| 51 | `delete_question` | Recursive delete; admin deletion is logged (admin_delete_post) | ×1 |  |
| 52 | `delete_tip` | Delete tip; admin deletion logged | ×2 |  |
| 53 | `edit_answer` | Author edits answer; reopens moderation reports | ×3 |  |
| 54 | `edit_tip` | Edit tip; reopens moderation reports | ×3 |  |
| 55 | `hospital_request` | Request a new hospital (dedupe/merge with similar pending, duplicateOf existing); quota hospital=10/ | ×11 |  |
| 56 | `admin_demo_wipe` | Wipe all demo accounts (real account-deletion path) and manifest docs; {more} when time budget excee | ×8 |  |
| 57 | `home_called` | Record call to home-care provider (+homeStats) | ×6 |  |
| 58 | `home_rate` | Rate home-care provider within 30 days of call | ×8 |  |
| 59 | `admin_labtests_set` | Add/edit one lab test template (hide, never delete) | ×3 |  |
| 60 | `lab_delete` | Delete own lab (not suspended) + prices + docs; strips home addresses | ×5 |  |
| 61 | `lab_ranges_set` | Lab: save own normal ranges | ×2 |  |
| 62 | `med_find_close` | Close my find | ×2 |  |
| 63 | `pharmacy_called` | Record call (10-min dedupe) + pharmacyStats | ×3 |  |
| 64 | `pharmacy_find_setting` | Opt in/out of medicine find requests (findOff) | ×3 |  |
| 65 | `rx_pharmacy_online_set` | Pharmacy: online selling enabled, radiusKm, fee, note | ×1 |  |
| 66 | `rx_shop_find` | Patient: find shop by 6-char shop code; quota medTry=30/day | ×4 |  |
| 67 | `stock_get` | Pharmacy: in-stock names | ×5 |  |

### ফাঁক ২ — Spring টেস্টে পাতলা (≤২টা লাইনে নাম, বা শুধু ভুলের পথ) আর e2e-তেও নেই

`admin_remove`, `ambreq_decline`, `web_logout`, `admin_bloodbank_add`, `admin_bloodbank_delete`, `admin_emergency_group` (শুধু ভুলের পথ), `admin_emergency_more`, `bloodbank_delete` (শুধু ভুলের পথ), `admin_hospital_requests`, `admin_hospitals`, `delete_question`, `delete_tip`, `lab_ranges_set`, `device_unregister`, `med_find_close`, `rx_pharmacy_online_set`, `phone_send_code`

### শুধু ভুলের পথে দেখা (ওয়েব যাত্রায় ডাকা হয়েছে কিন্তু সফল উত্তর নেই)

`rx_team_set`

### যাত্রায় ডাকা হয়েছে কিন্তু Spring টেস্টে নাম নেই (Spring-এ শুধু e2e দিয়ে ঢাকা)

—

## খ) অন্য HTTP endpoint

| path | Spring টেস্ট (নাম উল্লেখ) | ওয়েব যাত্রায় |
|---|---|---|
| `/.well-known/assetlinks.json` | ShareIT | — |
| `/api/admin/corrections` | BloodDemoSeedIT, BloodFlowIT | — |
| `/api/admin/resolve` | BloodFlowIT | 1 |
| `/api/auth/send-code` | AuthIT | 6 |
| `/api/auth/verify-code` | AuthIT, DemoTestBase | 132 |
| `/api/close-request` | BloodDemoSeedIT, BloodFlowIT | 1 |
| `/api/community` | AuthIT, CommunityDispatchIT, IntegrationTest | — |
| `/api/cron/daily` | AmbulanceNearIT, AppointmentsIT, CommunityDispatchIT, DeletionIT, EmergencyIT, RxPharmacyIT, ShareIT | — |
| `/api/cron/sentry-test` | **NONE** | — |
| `/api/cron/storage` | R2StorageIT, StorageOffIT | — |
| `/api/cron/storage/migrate` | R2StorageIT, StorageOffIT | — |
| `/api/cron/storage/probe` | R2StorageIT | — |
| `/api/edit-request` | BloodDemoSeedIT, BloodFlowIT | 1 |
| `/api/events` | SseKeepAliveIT | — |
| `/api/health` | CommunityDispatchIT | — |
| `/api/notify-request` | BloodFlowIT | 1 |
| `/api/respond` | BloodDemoSeedIT, BloodFlowIT, ShareIT | 2 |
| `/api/share` | ShareIT | — |
| `/r/{id}` | AmbulanceDemoIT, BloodDemoSeedIT, BloodFlowIT, BloodRulesTest, CommunityIT, HomecareIT, LabListingIT, ModerationIT, P2RulesTest, RxFlowIT, RxLabRulesTest, RxPharmacyIT, ShareIT, TestInfra | — |
| `/rx/{code}` | BloodRulesTest, RxFlowIT, RxPharmacyIT, ShareIT | — |
| `/shop/{code}` | DemoBooksIT, ShareIT, ShopBooksIT | — |

## গ) Android স্ক্রিন/ডায়ালগ কভারেজ

| নাম | ধরন | Robo | Maestro | অবস্থা |
|---|---|---|---|---|
| `AdminScreen` | screen | ✓ | j4-lab |  |
| `AdminOverviewTab` | tab |  | j4-lab |  |
| `AdminReportsTab` | tab |  |  | **NONE** |
| `AdminTicketsTab` | tab |  |  | **NONE** |
| `TicketSheet` | sheet |  |  | **NONE** |
| `AdminCorrectionsTab` | tab |  |  | **NONE** |
| `AdminEmergencyTab` | tab |  |  | **NONE** |
| `EmergencyCreateSheet` | sheet |  | j7-blood, j2-services |  |
| `AdminUsersTab` | tab |  | j2-services |  |
| `DestPicker` | picker |  | j2-services |  |
| `StarRatingDialog` | dialog |  |  | **NONE** |
| `ReasonReportDialog` | dialog |  | j4-lab |  |
| `MyAmbulancesSection` | section |  | j6-ambulance |  |
| `AmbulanceEditDialog` | dialog |  |  | **NONE** |
| `AmbulanceCallBar` | bar |  | j2-services |  |
| `ActiveRequestBanner` | banner |  |  | **NONE** |
| `AmbulanceRequestDialog` | dialog | ✓ | j2-services |  |
| `ReasonDialog` | dialog |  |  | **NONE** |
| `PhoneBookingDialog` | dialog |  |  | **NONE** |
| `AmbulanceRequestScreen` | screen |  | j8-profile |  |
| `AmbulanceCallsSection` | section |  |  | **NONE** |
| `AppointmentTab` | tab | ✓ | j1-patient, j5-doctor |  |
| `ProviderDetailDialog` | dialog | ✓ | j2-services, j1-patient |  |
| `ChamberPicker` | picker |  | j5-doctor |  |
| `BookDialog` | dialog |  |  | **NONE** |
| `MyAppointmentsScreen` | screen |  | j1-patient |  |
| `ChamberHubScreen` | screen | ✓ | j4-lab, j3-pharmacy, j1-patient, j5-doctor |  |
| `ChamberEditScreen` | screen | ✓ |  |  |
| `HospitalPickerDialog` | dialog | ✓ |  |  |
| `AddHospitalDialog` | dialog | ✓ |  |  |
| `FormDialog` | dialog | ✓ |  |  |
| `MyBloodBanksSection` | section |  |  | **NONE** |
| `BloodBankEditDialog` | dialog |  |  | **NONE** |
| `DetailSheet` | sheet |  |  | **NONE** |
| `CareScreen` | screen |  |  | **NONE** |
| `InviteDialog` | dialog |  |  | **NONE** |
| `MemberDialog` | dialog |  |  | **NONE** |
| `CareModeScreen` | screen |  | j4-lab, j3-pharmacy, j1-patient, j5-doctor |  |
| `SplashScreen` | screen |  |  | **NONE** |
| `EdgeToEdgeDialog` | dialog |  |  | **NONE** |
| `FullScreenDialog` | dialog |  |  | **NONE** |
| `CardDialog` | dialog |  |  | **NONE** |
| `AppBottomSheet` | sheet |  |  | **NONE** |
| `BackTopBar` | bar |  |  | **NONE** |
| `HealthDiscussionTab` | tab |  |  | **NONE** |
| `AskQuestionScreen` | screen |  |  | **NONE** |
| `QuestionDetailScreen` | screen |  | j4-lab |  |
| `HealthTipsTab` | tab |  |  | **NONE** |
| `TipDetailScreen` | screen |  | j4-lab |  |
| `PostTipScreen` | screen |  |  | **NONE** |
| `BadgeApplyDialog` | dialog |  |  | **NONE** |
| `ReportDialog` | dialog |  |  | **NONE** |
| `EditTextDialog` | dialog |  |  | **NONE** |
| `DonationDateDialog` | dialog | ✓ |  |  |
| `DemoSwitchDialog` | dialog |  | j8-profile |  |
| `EmergencyBanner` | banner |  |  | **NONE** |
| `FacilityDialog` | dialog |  | j8-profile, j2-services |  |
| `FilterBar` | bar | ✓ |  |  |
| `FilterSheet` | sheet |  |  | **NONE** |
| `AreaSheet` | sheet | ✓ |  |  |
| `HomeCareTab` | tab | ✓ |  |  |
| `InboxScreen` | screen |  |  | **NONE** |
| `LabSendDialog` | dialog |  |  | **NONE** |
| `HomeConfirmDialog` | dialog |  |  | **NONE** |
| `HomeDeclineDialog` | dialog |  |  | **NONE** |
| `HomeCancelDialog` | dialog |  |  | **NONE** |
| `LocationPicker` | picker |  |  | **NONE** |
| `MainScreen` | screen |  |  | **NONE** |
| `HomeTab` | tab |  |  | **NONE** |
| `MyRequestsScreen` | screen |  | j7-blood, j1-patient |  |
| `ModernNavBar` | bar | ✓ |  |  |
| `MedAlarmDialog` | dialog |  |  | **NONE** |
| `MedFindPanel` | panel | ✓ |  |  |
| `CloseRequestDialog` | dialog | ✓ |  |  |
| `MyRequestSection` | section |  | j7-blood, j1-patient |  |
| `RxCodeDialog` | dialog |  | j4-lab, j3-pharmacy, j1-patient |  |
| `PharmacyOrdersTab` | tab |  | j3-pharmacy |  |
| `MyLabPanel` | panel |  | j4-lab, j3-pharmacy |  |
| `LabInfoTab` | tab |  | j4-lab, j3-pharmacy |  |
| `LabRequestsTab` | tab |  |  | **NONE** |
| `MyPharmacyPanel` | panel |  | j3-pharmacy |  |
| `ShopTab` | tab |  | j4-lab, j3-pharmacy |  |
| `BuyModePicker` | picker |  | j2-services, j1-patient |  |
| `PharmacyRatingDialog` | dialog |  |  | **NONE** |
| `ShopEditScreen` | screen |  |  | **NONE** |
| `PillTabs` | tab | ✓ |  |  |
| `RxDetailDialog` | dialog |  |  | **NONE** |
| `MyPrescriptionsScreen` | screen |  | j1-patient |  |
| `RxClaimDialog` | dialog |  | j3-pharmacy |  |
| `DoctorPatientsTab` | tab |  |  | **NONE** |
| `PatientHistoryDialog` | dialog |  |  | **NONE** |
| `RxPatientAddDialog` | dialog |  |  | **NONE** |
| `RxDoctorTab` | tab |  | j4-lab, j3-pharmacy, j1-patient |  |
| `RxTeamDialog` | dialog |  |  | **NONE** |
| `RxEditorDialog` | dialog |  | j4-lab, j3-pharmacy, j1-patient, j5-doctor |  |
| `MedEditDialog` | dialog |  |  | **NONE** |
| `RxAssistScreen` | screen |  |  | **NONE** |
| `ProfileTab` | tab |  | j8-profile, j3-pharmacy, j7-blood, j1-patient |  |
| `LateDialog` | dialog |  |  | **NONE** |
| `QueuePanel` | panel |  |  | **NONE** |
| `BagsPicker` | picker |  |  | **NONE** |
| `NewRequestScreen` | screen |  |  | **NONE** |
| `RequestDetailScreen` | screen |  | j4-lab, j8-profile |  |
| `MyRequestDetailScreen` | screen |  |  | **NONE** |
| `DonationChecklistDialog` | dialog |  |  | **NONE** |
| `EditRequestScreen` | screen |  |  | **NONE** |
| `RxActivitySection` | section |  | j1-patient |  |
| `PharmacyPickDialog` | dialog |  |  | **NONE** |
| `OnlineOrderDialog` | dialog |  |  | **NONE** |
| `WithdrawDialog` | dialog |  |  | **NONE** |
| `LabPickDialog` | dialog |  |  | **NONE** |
| `LabReportDialog` | dialog |  |  | **NONE** |
| `PayDialog` | dialog |  |  | **NONE** |
| `AcceptOrderDialog` | dialog |  |  | **NONE** |
| `OnlineSettingsDialog` | dialog |  |  | **NONE** |
| `MissedDialog` | dialog |  |  | **NONE** |
| `DispenseDialog` | dialog |  | j3-pharmacy |  |
| `StockDialog` | dialog |  |  | **NONE** |
| `RxSentDialog` | dialog |  | j4-lab, j8-profile, j3-pharmacy, j1-patient, j5-doctor |  |
| `SendErrorBar` | bar |  |  | **NONE** |
| `RxPickDialog` | dialog |  | j5-doctor |  |
| `PharmacySendDialog` | dialog |  | j1-patient, j5-doctor |  |
| `LabInfoSheet` | sheet |  | j1-patient |  |
| `LabEditScreen` | screen |  |  | **NONE** |
| `LabReportEntryDialog` | dialog |  | j4-lab, j1-patient |  |
| `ReviewReadDialog` | dialog |  |  | **NONE** |
| `ReviewSettingsDialog` | dialog |  |  | **NONE** |
| `RxReviewsScreen` | screen |  | j4-lab |  |
| `LoginScreen` | screen | ✓ |  |  |
| `ProfileEditScreen` | screen | ✓ | j8-profile |  |
| `HealthTab` | tab | ✓ |  |  |
| `ServicesTab` | tab | ✓ | j4-lab, j3-pharmacy, j2-services, j1-patient, j6-ambulance |  |
| `SettingsScreen` | screen |  |  | **NONE** |
| `HelpScreen` | screen | ✓ |  |  |
| `UpdateBanner` | banner |  |  | **NONE** |
| `PhoneVerifyDialog` | dialog | ✓ |  |  |
| `WorkTab` | tab | ✓ | j4-lab, j3-pharmacy, j5-doctor, j6-ambulance |  |
| `MyBusinessScreen` | screen |  |  | **NONE** |
| `AddBusinessDialog` | dialog | ✓ |  |  |

ইনলাইন ডায়ালগগুলোর (FormDialog/AlertDialog …) আলাদা কোনো স্বয়ংক্রিয় টেস্ট নেই — হাতে টেস্টের ফাইলগুলোতে ঢাকা।

## ঘ) Vue পাতা/ডায়ালগ — আসল সার্ভারের যাত্রায় লেখা মেলে কি না

Vue-র নিজের Playwright (নকল API) সব পাতা খোলে; নিচে শুধু দেখা হয়েছে e2e যাত্রার লেখা এই কম্পোনেন্টের কোনো বাংলা লেখার সাথে মেলে কি না।

| কম্পোনেন্ট | ধরন | যাত্রা |
|---|---|---|
| `ProfileForm` | form | 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `DemoSwitchDialog` | dialog | 07-health, 02-blood |
| `AccountSection` | section | **NONE** |
| `CarePage` | page | 04-caregiver, 07-health, 05-services, 02-blood, 08-extra, 06-rx |
| `DonationDateDialog` | dialog | 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra |
| `EditProfilePage` | page | 07-health, 05-services, 02-blood, 08-extra, 06-rx |
| `HelpPage` | page | 07-health, 02-blood, 06-rx |
| `InboxPage` | page | 04-caregiver, 07-health, 02-blood, 01-core, 08-extra, 06-rx |
| `InviteDialog` | dialog | 04-caregiver |
| `LoginPage` | page | **NONE** |
| `MemberDialog` | dialog | 07-health, 06-rx |
| `PhoneVerifyDialog` | dialog | **NONE** |
| `PhotoMenuDialog` | dialog | **NONE** |
| `ProfilePage` | page | 04-caregiver, 07-health, 02-blood, 01-core, 06-rx |
| `SettingsPage` | page | 03-ambulance, 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `AddHospitalDialog` | dialog | 02-blood |
| `AppointmentSection` | section | **NONE** |
| `AreaSheet` | sheet | 03-ambulance, 05-services, 02-blood, 01-core, 06-rx |
| `BookDialog` | dialog | 04-caregiver, 01-core, 06-rx |
| `ChamberPicker` | picker | 04-caregiver, 01-core, 08-extra, 06-rx |
| `DoctorsPage` | page | 03-ambulance, 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `FilterSheet` | sheet | **NONE** |
| `HomeCarePage` | page | 03-ambulance, 07-health, 05-services, 02-blood, 01-core, 06-rx |
| `HospitalPickerDialog` | dialog | **NONE** |
| `LateDialog` | dialog | 01-core |
| `MyAppointmentsPage` | page | 04-caregiver, 07-health, 02-blood, 01-core, 06-rx |
| `PhoneVerifyDialog` | dialog | **NONE** |
| `QueuePanel` | panel | **NONE** |
| `ReasonReportDialog` | dialog | 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `SpecialtyPicker` | picker | 01-core, 06-rx |
| `StarRatingDialog` | dialog | 03-ambulance, 05-services |
| `BookingsTab` | tab | 03-ambulance, 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `ConfirmApptDialog` | dialog | 04-caregiver, 05-services, 02-blood, 01-core, 08-extra |
| `HomeCareTab` | tab | 03-ambulance, 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `PatientsTab` | tab | **NONE** |
| `UpazilaPicker` | picker | 04-caregiver, 07-health, 05-services, 02-blood, 08-extra, 06-rx |
| `BloodSection` | section | **NONE** |
| `DonationChecklistDialog` | dialog | 02-blood |
| `MyRequestSection` | section | 05-services, 02-blood |
| `MyRequestsPage` | page | 02-blood, 01-core |
| `AddHospitalDialog` | dialog | 02-blood |
| `BagsPicker` | picker | **NONE** |
| `EditRequestForm` | form | 07-health, 05-services, 02-blood, 08-extra |
| `EditRequestPage` | page | 02-blood |
| `NewRequestForm` | form | 03-ambulance, 07-health, 05-services, 02-blood, 01-core, 06-rx |
| `NewRequestPage` | page | 02-blood |
| `CloseRequestDialog` | dialog | 07-health, 05-services, 02-blood, 08-extra |
| `ShareDialog` | dialog | 03-ambulance, 05-services |
| `AskPage` | page | 07-health |
| `BadgePage` | page | 07-health, 08-extra |
| `DiscussionPage` | page | 07-health, 02-blood, 08-extra |
| `EditTextDialog` | dialog | 07-health, 05-services, 02-blood, 08-extra |
| `HealthReportDialog` | dialog | **NONE** |
| `HealthSection` | section | **NONE** |
| `HealthTabs` | tab | 07-health, 02-blood, 01-core, 08-extra, 06-rx |
| `LabPickDialog` | dialog | 03-ambulance, 04-caregiver, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `LabReportDialog` | dialog | 01-core |
| `LabSendDialog` | dialog | 04-caregiver, 07-health, 01-core, 06-rx |
| `OnlineOrderDialog` | dialog | 04-caregiver, 07-health, 05-services, 01-core, 06-rx |
| `PayDialog` | dialog | 01-core |
| `PharmacyPickDialog` | dialog | 03-ambulance, 04-caregiver, 05-services, 02-blood, 01-core, 06-rx |
| `PharmacySendDialog` | dialog | 04-caregiver, 07-health, 05-services, 01-core, 08-extra, 06-rx |
| `PostTipPage` | page | 07-health |
| `PrescriptionsPage` | page | 07-health, 01-core, 06-rx |
| `RxActivitySection` | section | 04-caregiver, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `RxClaimDialog` | dialog | 04-caregiver, 07-health, 05-services, 01-core, 06-rx |
| `RxPickDialog` | dialog | 04-caregiver, 07-health, 05-services, 01-core, 08-extra, 06-rx |
| `RxSentDialog` | dialog | 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `TipsPage` | page | 07-health, 02-blood, 08-extra, 06-rx |
| `WithdrawDialog` | dialog | 04-caregiver, 05-services, 01-core |
| `DoctorReportDialog` | dialog | 01-core |
| `PatientAddDialog` | dialog | 04-caregiver, 07-health, 01-core, 06-rx |
| `PatientsPage` | page | 01-core, 06-rx |
| `ReviewReadDialog` | dialog | 01-core, 06-rx |
| `ReviewSettingsDialog` | dialog | 06-rx |
| `ReviewsPage` | page | 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `RxListPage` | page | 03-ambulance, 04-caregiver, 07-health, 05-services, 01-core, 06-rx |
| `RxViewPage` | page | 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `TeamPage` | page | 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `WritePage` | page | **NONE** |
| `AcceptDialog` | dialog | 05-services, 01-core |
| `CancelOrderDialog` | dialog | 06-rx |
| `DispenseForm` | form | 05-services, 01-core, 08-extra, 06-rx |
| `HomeConfirmDialog` | dialog | 05-services, 01-core, 08-extra, 06-rx |
| `MissedPanel` | panel | 05-services |
| `OnlineForm` | form | 07-health, 05-services, 02-blood, 08-extra, 06-rx |
| `OnlineSettingsPanel` | panel | 05-services |
| `OrdersTab` | tab | 05-services, 01-core, 06-rx |
| `Panel` | panel | **NONE** |
| `PartnerPage` | page | **NONE** |
| `PriceListPanel` | panel | 04-caregiver, 07-health, 00-seed, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `PriceSettingsPanel` | panel | 08-extra |
| `RxPanel` | panel | 07-health, 02-blood, 06-rx |
| `ShopCodePanel` | panel | 05-services |
| `AddPanel` | panel | 05-services |
| `AdjustDialog` | dialog | 07-health, 05-services, 02-blood, 08-extra, 06-rx |
| `BulkPanel` | panel | 05-services, 01-core, 06-rx |
| `BuyTab` | tab | 07-health, 05-services, 02-blood, 08-extra |
| `ItemDialog` | dialog | 07-health, 05-services, 02-blood, 08-extra, 06-rx |
| `ItemPicker` | picker | 05-services, 01-core, 08-extra, 06-rx |
| `ItemsTab` | tab | 05-services, 08-extra |
| `ReportTab` | tab | 05-services, 08-extra |
| `SalesTab` | tab | 05-services, 08-extra |
| `ActiveBanner` | banner | **NONE** |
| `BuyModePicker` | picker | 05-services, 01-core |
| `CallBar` | bar | **NONE** |
| `DestPicker` | picker | 07-health, 02-blood, 01-core |
| `FacilityDialog` | dialog | 05-services, 02-blood, 01-core |
| `FilterSheet` | sheet | 03-ambulance, 05-services, 02-blood, 01-core, 06-rx |
| `PickCarDialog` | dialog | **NONE** |
| `RatingDialog` | dialog | 03-ambulance, 05-services |
| `ReasonDialog` | dialog | 02-blood, 06-rx |
| `ReportDialog` | dialog | 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `RequestDialog` | dialog | 07-health |
| `RequestPage` | page | 03-ambulance, 07-health, 05-services, 02-blood, 01-core, 06-rx |
| `ServiceTabs` | tab | 03-ambulance, 04-caregiver, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `ServicesSection` | section | **NONE** |
| `AmbulanceForm` | form | 07-health, 02-blood, 06-rx |
| `BizPage` | page | **NONE** |
| `BloodBankForm` | form | 07-health, 02-blood, 06-rx |
| `LabForm` | form | 05-services |
| `LabHospitalPicker` | picker | 01-core, 06-rx |
| `LabTabs` | tab | 04-caregiver, 07-health, 05-services, 02-blood, 01-core, 08-extra, 06-rx |
| `PharmacyForm` | form | **NONE** |
| `PharmacyTabs` | tab | 05-services, 01-core, 08-extra, 06-rx |
| `StatsDialog` | dialog | 03-ambulance |
| `WorkSection` | section | **NONE** |
| `FormPage` | page | **NONE** |
| `SubPageBar` | bar | **NONE** |
| `AreaPicker` | picker | 03-ambulance, 05-services, 02-blood, 01-core, 06-rx |
| `ConfirmDialog` | dialog | **NONE** |
| `Dialog` | dialog | **NONE** |
| `FilterBar` | bar | 03-ambulance, 05-services, 02-blood, 01-core, 06-rx |
| `PillTabs` | tab | **NONE** |
| `PlacePicker` | picker | **NONE** |

## ঙ) পুশ, ইমেইল, ক্রন, লিংক, permission, সেটিংস

| জিনিস | স্বয়ংক্রিয় টেস্ট | ফাঁক |
|---|---|---|
| পুশ — সার্ভারে তৈরি | type-এর নাম টেস্টে আছে: answer, badge, care, correction, emergency, hospital, med_find, request, request_removed, response, rx, rx_lab, rx_review, services, support | বাকি: (helper default), amb_req_mine, appointment, eligible, med_find_mine, rx_pharmacy |
| পুশ — FCM দিয়ে ফোনে পৌঁছানো | **NONE** (CI-তে `PUSH_MODE=fake`) | হাতে + প্রোডাকশন ডেলিভারি চেক |
| পুশ — চাপলে সঠিক পাতা (`openFromData`) | **NONE** | হাতে টেস্ট N-সিরিজ |
| ইমেইল — লগইন কোড | `AuthIT` (নকল) | আসল SES ডেলিভারি, Spam, sandbox |
| ক্রন/DailyTask | টেস্টে ক্লাসের নাম: AppointmentReminders, RxOrderPrivacyTask, MedFindCleanupTask; `/api/cron/daily` রানার টেস্ট আছে কি না নিচে | প্রোডাকশনে রোজ চলা আর ফল দেখা — **NONE** |
| শেয়ার লিংক `/r`, `/rx`, `/shop`, QR, assetlinks | `ShareIT` | App Link (release key) যাচাই **NONE** |
| permission prompt (লোকেশন, নোটিফিকেশন) | **NONE** | হাতে |
| সেটিংস (ভাষা, থিম, নোটিফিকেশনের পছন্দ) | আংশিক (যাত্রা ০৭) | অ্যাপে **NONE** |
| অফলাইন/ধীর নেটওয়ার্ক | **NONE** | হাতে |
| ছোট পর্দা, ডার্ক থিম | Vue mocked (mobile/desktop), Robo (কিছু) | Android ছোট পর্দা/বড় লেখা **NONE** |
| লোড/স্ট্রেস | **NONE** | 05-non-functional |
| ব্যাকআপ ফেরত | Backup check workflow (সাপ্তাহিক) | পুরো VPS হারানোর মহড়া **NONE** |
| Vue ওয়েব + Spring একসাথে (আসল স্ট্যাক) | **NONE** (CI-র যাত্রা React ওয়েবে; Vue-র যাত্রা শুধু Node-এ, হাতে) | বড় ফাঁক |
| Android + Spring | Maestro (প্রোডাকশন ডেমো, ৮ যাত্রা, বেশিরভাগ পড়া) | লেখার কাজ, নোটিফিকেশন **NONE** |
