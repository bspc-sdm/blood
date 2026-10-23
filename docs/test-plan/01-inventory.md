# ১. ইনভেন্টরি — কোডে যা আছে তার পুরো তালিকা

> এই ফাইল কোড থেকে বানানো: `python3 docs/test-plan/tools/build_inventory.py` (হাতে বদলাবেন না — RC-র কোডে আবার চালান)।


## সংখ্যা এক নজরে

| কী | কত | কোথা থেকে গোনা |
|---|---|---|
| Android নেভিগেশন রুট (`Screen` sealed) | 26 | `app/.../MainActivity.kt` |
| Android Composable স্ক্রিন/ডায়ালগ/শিট/ট্যাব… | 139 (dialog 59, screen 29, tab 22, sheet 7, picker 5, section 5, bar 5, panel 4, banner 3) | `@Composable fun …Screen/Dialog/Sheet/Tab…` |
| Android ইনলাইন ডায়ালগ/শিট (FormDialog, AlertDialog …) | 92 | কল-সাইট |
| ওয়েব (Vue) রুট/পাথ | 16 টপ-লেভেল, 86 পাথ | `web-vue/src/router.ts` + সেকশনের লিংক |
| ওয়েব (Vue) কম্পোনেন্ট ফাইল | 365 (part 230, dialog 49, page 29, tab 13, picker 10, panel 10, form 9, section 8, sheet 3, bar 3, banner 1) | `web-vue/src/**/*.vue` |
| অ্যাডমিন প্যানেলের পাতা | 18 (+ verify, ticket, user detail) | `web-vue/admin/src/Shell.ts` |
| `/api/community` action | 225 (অতিথি 15, কেয়ারগিভার 25) | `ActionModule` ম্যাপ |
| অন্য HTTP endpoint | 21 | `@…Mapping` |
| পুশ নোটিফিকেশনের ধরন (type) | 21 (কল-সাইট 73) | `"type" to …` |
| ইমেইলের ধরন | 1 (লগইন কোড) | `MailSender` |
| ক্রন/ব্যাকগ্রাউন্ড কাজ | 16 (+ VPS টাইমার ও CI ক্রন — নিচে) | `@Recurring`, `DailyTask` |
| Android permission | 5 | `AndroidManifest.xml` |

## ক) রোল (কে কে অ্যাপ ব্যবহার করেন)

| রোল | কীভাবে হয় | কোড যেখানে চেনে | ডেমো অ্যাকাউন্ট |
|---|---|---|---|
| অতিথি (guest) | লগইন ছাড়া | `ActionPolicy.PUBLIC_ACTIONS` (১৫টা) | — |
| সাধারণ ব্যবহারকারী / রোগী | ইমেইল কোডে লগইন | যেকোনো Bearer token | ডেমো রোগী রহিম (`patient`) |
| রক্তদাতা | প্রোফাইলে গ্রুপ, জন্মতারিখ, ওজন; ১৮–৬০ বছর, ≥৫০ কেজি, শেষ দানের ১২০ দিন পর | `BloodRules.isEligibleDonor` | ডেমো ডোনার করিম (`donor`) |
| কেয়ারগিভার | রোগী ইমেইলে আমন্ত্রণ দেন, কেয়ারগিভার রাজি হন | `body.asUid` + `CARE_ACTIONS` (২৫টা) + সক্রিয় `care_links` | ডেমো কেয়ারগিভার রুমা (`caregiver`) |
| ডাক্তার | ব্যাজ আবেদন (`doctor`) → অ্যাডমিন অনুমোদন | `approvedBadge == doctor` | ডা. ডেমো হাসান (`doctor`) |
| ডাক্তারের সহকারী | ডাক্তার সহকারীর ইমেইল যোগ করেন (যাচাই করা ইমেইল) | `actingFor` / `doctorOrAssistant` | ডেমো সহকারী সুমন (`assistant`) |
| নার্স / পুষ্টিবিদ / ট্রেইনার / বিশেষজ্ঞ | ব্যাজ আবেদন | `PROVIDER_TYPES`, `CHAMBER_TYPES` | ডেমো নার্স নাসরিন (`homecare`) |
| ফার্মেসির মালিক | ফার্মেসি যোগ → অ্যাডমিন অনুমোদন | `myPharmacy` / `myShop` (status approved) | ডেমো ফার্মেসির মালিক (`pharmacy`) |
| প্যাথলজির মালিক | প্যাথলজি যোগ → অনুমোদন | `myLab` | ডেমো প্যাথলজির মালিক (`lab`) |
| অ্যাম্বুলেন্স মালিক/চালক | অ্যাম্বুলেন্স যোগ → অনুমোদন | `ownerUid`, `ambreq_targets` | ডেমো অ্যাম্বুলেন্স চালক (`ambulance`) |
| ব্লাড ব্যাংক | ব্লাড ব্যাংক যোগ → অনুমোদন | `owner_uid` | ডেমো ব্লাড ব্যাংক (`bloodbank`) |
| অ্যাডমিন | মালিক ইমেইল যোগ করেন (`admin_add`) | `AdminService.isAdmin` (যাচাই করা ইমেইল) | নেই — মালিকের নিজের অ্যাকাউন্ট |
| মালিক (owner) | সার্ভারের `ADMIN_EMAILS` | `AdminService.isOwner` | নেই |
| অ্যাডমিন ওয়েব-কুকি | `/admin` প্যানেলে ইমেইল কোড | `__Host-lf_admin` + হেডার `x-lifolo-admin: 1` | — |
| ডেমো রোল (১০টা) | লগইন পাতায় "ডেমো (পরীক্ষার জন্য)" — কোড ছাড়াই | `DemoLoginService`, uid `demo-…`, `DemoWorld.sameWorld` | সব — **রিলিজের আগে মুছতে হবে** |

## খ) Android — নেভিগেশন রুট (`sealed interface Screen`)

`Loading`, `Login`, `EditProfile`, `PickLocation`, `Main`, `NewRequest`, `RequestDetail`, `MyRequestDetail`, `MyRequests`, `Admin`, `Settings`, `QuestionDetail`, `AskQuestion`, `TipDetail`, `PostTip`, `MyAppointments`, `MyPrescriptions`, `Care`, `CareFor`, `RxAssist`, `RxReviews`, `ChamberEdit`, `MyBusiness`, `Inbox`, `Help`, `AmbulanceRequest`

নিচের বার: হোম · অ্যাপয়েন্টমেন্ট · স্বাস্থ্য · সেবা · আমার কাজ (শুধু যাঁর ব্যবসা/চেম্বার আছে); প্রোফাইল উপরের ছবিতে (`Tabs.PROFILE`)।

## গ) Android — Composable স্ক্রিন, ডায়ালগ, শিট, ট্যাব

| # | নাম | ধরন | ফাইল:লাইন | প্রথম বাংলা লেখা (শিরোনাম/বাটন) | কোথা থেকে ডাকা হয় (বার) |
|---|---|---|---|---|---|
| 1 | `AdminScreen` | screen | `AdminMobile.kt:172` | অ্যাডমিন | 1 |
| 2 | `AdminOverviewTab` | tab | `AdminMobile.kt:263` | এখানেই করা যায় | 1 |
| 3 | `AdminReportsTab` | tab | `AdminMobile.kt:299` | দেখার মতো কোনো রিপোর্ট নেই | 1 |
| 4 | `AdminTicketsTab` | tab | `AdminMobile.kt:329` | খোলা কোনো সাপোর্টের বার্তা নেই | 1 |
| 5 | `TicketSheet` | sheet | `AdminMobile.kt:356` | Lifolo টিম | 1 |
| 6 | `AdminCorrectionsTab` | tab | `AdminMobile.kt:405` | অপেক্ষায় কোনো তারিখ সংশোধন নেই | 1 |
| 7 | `AdminEmergencyTab` | tab | `AdminMobile.kt:426` | নতুন জরুরি আবেদন | 1 |
| 8 | `EmergencyCreateSheet` | sheet | `AdminMobile.kt:497` | জরুরি আবেদন | 1 |
| 9 | `AdminUsersTab` | tab | `AdminMobile.kt:590` | ইমেইল বা ফোন নম্বর | 1 |
| 10 | `DestPicker` | picker | `AmbFare.kt:147` | অন্য হাসপাতাল | 1 |
| 11 | `StarRatingDialog` | dialog | `Ambulance.kt:592` | মন্তব্য (ঐচ্ছিক) | 3 |
| 12 | `ReasonReportDialog` | dialog | `Ambulance.kt:636` | সমস্যা জানান | 6 |
| 13 | `MyAmbulancesSection` | section | `Ambulance.kt:675` | বদলান | 2 |
| 14 | `AmbulanceEditDialog` | dialog | `Ambulance.kt:791` | অ্যাম্বুলেন্স যোগ | 1 |
| 15 | `AmbulanceCallBar` | bar | `AmbulanceRequest.kt:373` | অ্যাম্বুলেন্স ডাকুন | 1 |
| 16 | `ActiveRequestBanner` | banner | `AmbulanceRequest.kt:408` | আপনার অ্যাম্বুলেন্সের অনুরোধ চলছে | 1 |
| 17 | `AmbulanceRequestDialog` | dialog | `AmbulanceRequest.kt:436` | অ্যাম্বুলেন্স ডাকুন | 1 |
| 18 | `ReasonDialog` | dialog | `AmbulanceRequest.kt:635` | বাতিল করুন | 2 |
| 19 | `PhoneBookingDialog` | dialog | `AmbulanceRequest.kt:664` | হ্যাঁ —  | 2 |
| 20 | `AmbulanceRequestScreen` | screen | `AmbulanceRequest.kt:694` | আরও ${num(added)}টা গাড়িকে জানানো হয়েছে | 1 |
| 21 | `AmbulanceCallsSection` | section | `AmbulanceRequest.kt:1026` | ডাক | 1 |
| 22 | `AppointmentTab` | tab | `Appointment.kt:73` | ফিরে যান | 1 |
| 23 | `ProviderDetailDialog` | dialog | `Appointment.kt:405` | অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন | 2 |
| 24 | `ChamberPicker` | picker | `Appointment.kt:580` | চেম্বার বেছে অ্যাপয়েন্টমেন্ট নিন | 1 |
| 25 | `BookDialog` | dialog | `Appointment.kt:637` | কার জন্য | 1 |
| 26 | `MyAppointmentsScreen` | screen | `Appointment.kt:726` | আমার অ্যাপয়েন্টমেন্ট | 1 |
| 27 | `ChamberHubScreen` | screen | `Appointment.kt:909` | আমার চেম্বার | 1 |
| 28 | `ChamberEditScreen` | screen | `Appointment.kt:986` | আমার বাসায় সেবা ও প্রোফাইল | 2 |
| 29 | `HospitalPickerDialog` | dialog | `AreaPicker.kt:179` | হাসপাতাল বেছে নিন | 4 |
| 30 | `AddHospitalDialog` | dialog | `AreaPicker.kt:280` | হাসপাতাল যোগের অনুরোধ | 2 |
| 31 | `FormDialog` | dialog | `AreaPicker.kt:479` |  | 34 |
| 32 | `MyBloodBanksSection` | section | `BloodBank.kt:258` | বদলান | 1 |
| 33 | `BloodBankEditDialog` | dialog | `BloodBank.kt:323` | ব্লাড ব্যাংক যোগ | 1 |
| 34 | `DetailSheet` | sheet | `Cards.kt:256` |  | 4 |
| 35 | `CareScreen` | screen | `CareAccess.kt:115` | কেয়ারগিভার ও পরিবার | 1 |
| 36 | `InviteDialog` | dialog | `CareAccess.kt:293` | কেয়ারগিভার যোগ করুন | 1 |
| 37 | `MemberDialog` | dialog | `CareAccess.kt:330` | পরিবারের সদস্য | 1 |
| 38 | `CareModeScreen` | screen | `CareAccess.kt:377` | রোগী | 1 |
| 39 | `SplashScreen` | screen | `Common.kt:124` |  | 2 |
| 40 | `EdgeToEdgeDialog` | dialog | `Common.kt:166` |  | 3 |
| 41 | `FullScreenDialog` | dialog | `Common.kt:241` |  | 15 |
| 42 | `CardDialog` | dialog | `Common.kt:260` |  | 4 |
| 43 | `AppBottomSheet` | sheet | `Common.kt:297` |  | 3 |
| 44 | `BackTopBar` | bar | `Common.kt:308` |  | 40 |
| 45 | `HealthDiscussionTab` | tab | `Community.kt:336` | স্বাস্থ্য আলোচনা | 1 |
| 46 | `AskQuestionScreen` | screen | `Community.kt:430` | প্রশ্ন করুন | 1 |
| 47 | `QuestionDetailScreen` | screen | `Community.kt:484` | প্রশ্ন | 1 |
| 48 | `HealthTipsTab` | tab | `Community.kt:711` | হেলথ টিপস | 1 |
| 49 | `TipDetailScreen` | screen | `Community.kt:808` | হেলথ টিপস | 1 |
| 50 | `PostTipScreen` | screen | `Community.kt:897` | টিপস দিন | 1 |
| 51 | `BadgeApplyDialog` | dialog | `Community.kt:952` | ব্যাজের তথ্য সংশোধন | 1 |
| 52 | `ReportDialog` | dialog | `Community.kt:1067` | রিপোর্ট করবেন? | 2 |
| 53 | `EditTextDialog` | dialog | `Community.kt:1084` | শিরোনাম | 3 |
| 54 | `DonationDateDialog` | dialog | `Corrections.kt:60` | শেষ রক্তদানের তারিখ | 1 |
| 55 | `DemoSwitchDialog` | dialog | `DemoLogin.kt:122` | রোল বদলান | 1 |
| 56 | `EmergencyBanner` | banner | `Emergency.kt:99` |  | 1 |
| 57 | `FacilityDialog` | dialog | `FacilityPicker.kt:109` | হাসপাতাল, ক্লিনিক বা জায়গার নাম | 2 |
| 58 | `FilterBar` | bar | `FilterBar.kt:65` | এলাকা বদলান | 8 |
| 59 | `FilterSheet` | sheet | `FilterBar.kt:145` | সব মুছুন | 5 |
| 60 | `AreaSheet` | sheet | `FilterBar.kt:185` | ফিরে যান | 8 |
| 61 | `HomeCareTab` | tab | `HomeCare.kt:46` | বাসায় ডাক্তার | 1 |
| 62 | `InboxScreen` | screen | `Inbox.kt:101` | নোটিফিকেশন | 1 |
| 63 | `LabSendDialog` | dialog | `LabPrice.kt:165` | $title — সময় বদলান | 2 |
| 64 | `HomeConfirmDialog` | dialog | `LabPrice.kt:230` | সময় নিশ্চিত করুন | 1 |
| 65 | `HomeDeclineDialog` | dialog | `LabPrice.kt:278` | বাসায় যেতে পারবেন না? | 1 |
| 66 | `HomeCancelDialog` | dialog | `LabPrice.kt:309` | বাসা থেকে নমুনা লাগবে না? | 1 |
| 67 | `LocationPicker` | picker | `LocationPicker.kt:39` | লোকেশন পাওয়া যায়নি। GPS চালু করুন বা জেলা বেছে নিন। | 2 |
| 68 | `MainScreen` | screen | `MainScreen.kt:59` |  | 1 |
| 69 | `HomeTab` | tab | `MainScreen.kt:197` |  | 2 |
| 70 | `MyRequestsScreen` | screen | `MainScreen.kt:348` | লোড হয়নি | 1 |
| 71 | `ModernNavBar` | bar | `MainScreen.kt:407` |  | 1 |
| 72 | `MedAlarmDialog` | dialog | `MedAlarms.kt:216` | ওষুধের অ্যালার্ম | 1 |
| 73 | `MedFindPanel` | panel | `MedFind.kt:137` | ওষুধের নাম | 1 |
| 74 | `CloseRequestDialog` | dialog | `MyRequestUi.kt:245` | রক্ত আর লাগবে না? | 2 |
| 75 | `MyRequestSection` | section | `MyRequestUi.kt:327` | আমার রিকোয়েস্ট | 1 |
| 76 | `RxCodeDialog` | dialog | `MyWorkTabs.kt:160` | প্রেসক্রিপশন খুলুন | 2 |
| 77 | `PharmacyOrdersTab` | tab | `MyWorkTabs.kt:241` | রোগীকে জানানো হয়েছে | 1 |
| 78 | `MyLabPanel` | panel | `MyWorkTabs.kt:428` | অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন। | 1 |
| 79 | `LabInfoTab` | tab | `MyWorkTabs.kt:517` | বাসা থেকে নমুনা নেয় | 2 |
| 80 | `LabRequestsTab` | tab | `MyWorkTabs.kt:553` | রোগী কাগজের প্রেসক্রিপশন নিয়ে এলে কোড দিয়ে খুলুন — পরীক্ষাগুলো এখানে যোগ হবে। | 1 |
| 81 | `MyPharmacyPanel` | panel | `Pharmacy.kt:275` | অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন। | 1 |
| 82 | `ShopTab` | tab | `Pharmacy.kt:448` | অনুমোদনের পর তালিকায় দেখাবে। | 2 |
| 83 | `BuyModePicker` | picker | `Pharmacy.kt:586` | কীভাবে কিনবেন? | 1 |
| 84 | `PharmacyRatingDialog` | dialog | `Pharmacy.kt:907` | দোকানের সেবা নিয়ে — ওষুধ কেমন কাজ করল তা নয় | 1 |
| 85 | `ShopEditScreen` | screen | `Pharmacy.kt:969` | ফার্মেসি খুলুন | 1 |
| 86 | `PillTabs` | tab | `PillTabs.kt:58` |  | 15 |
| 87 | `RxDetailDialog` | dialog | `Prescriptions.kt:387` |  | 3 |
| 88 | `MyPrescriptionsScreen` | screen | `Prescriptions.kt:520` | প্রেসক্রিপশনটা খোলা যাচ্ছে না — হয়তো মুছে ফেলা হয়েছে | 2 |
| 89 | `RxClaimDialog` | dialog | `Prescriptions.kt:598` | প্রেসক্রিপশন যোগ করুন | 1 |
| 90 | `DoctorPatientsTab` | tab | `Prescriptions.kt:766` |  | 1 |
| 91 | `PatientHistoryDialog` | dialog | `Prescriptions.kt:788` | রোগী | 1 |
| 92 | `RxPatientAddDialog` | dialog | `Prescriptions.kt:826` | নতুন রোগী | 1 |
| 93 | `RxDoctorTab` | tab | `Prescriptions.kt:882` | প্রেসক্রিপশন | 1 |
| 94 | `RxTeamDialog` | dialog | `Prescriptions.kt:940` | সহকারী | 1 |
| 95 | `RxEditorDialog` | dialog | `Prescriptions.kt:1004` | প্রেসক্রিপশন | 2 |
| 96 | `MedEditDialog` | dialog | `Prescriptions.kt:1167` | ওষুধ যোগ করুন | 1 |
| 97 | `RxAssistScreen` | screen | `Prescriptions.kt:1254` | ডাক্তারের সহকারী | 1 |
| 98 | `ProfileTab` | tab | `Profile.kt:50` | ছবি আপলোড হয়নি | 1 |
| 99 | `LateDialog` | dialog | `Queue.kt:242` | দেরি হবে | 1 |
| 100 | `QueuePanel` | panel | `Queue.kt:298` |  | 2 |
| 101 | `BagsPicker` | picker | `RequestScreens.kt:58` |  | 2 |
| 102 | `NewRequestScreen` | screen | `RequestScreens.kt:119` | রোগী কোথায় আছেন? | 1 |
| 103 | `RequestDetailScreen` | screen | `RequestScreens.kt:285` | লোড হয়নি | 1 |
| 104 | `MyRequestDetailScreen` | screen | `RequestScreens.kt:641` |  | 1 |
| 105 | `DonationChecklistDialog` | dialog | `RequestScreens.kt:854` | আজ সুস্থ বোধ করছি, ভালো ঘুম ও খাওয়া হয়েছে | 1 |
| 106 | `EditRequestScreen` | screen | `RequestScreens.kt:892` | রিকোয়েস্ট এডিট | 1 |
| 107 | `RxActivitySection` | section | `RxNet.kt:517` | ফার্মেসিতে পাঠান | 1 |
| 108 | `PharmacyPickDialog` | dialog | `RxNet.kt:775` | অনলাইনে অর্ডার | 1 |
| 109 | `OnlineOrderDialog` | dialog | `RxNet.kt:916` | ডেলিভারি:  | 3 |
| 110 | `WithdrawDialog` | dialog | `RxNet.kt:961` | ${o.pharmacyName} থেকে ফিরিয়ে নেবেন? | 1 |
| 111 | `LabPickDialog` | dialog | `RxNet.kt:1000` | প্যাথলজি বাছুন | 3 |
| 112 | `LabReportDialog` | dialog | `RxNet.kt:1104` | পরীক্ষার রিপোর্ট | 3 |
| 113 | `PayDialog` | dialog | `RxNet.kt:1237` | রিপোর্ট দেখানোর টাকা | 1 |
| 114 | `AcceptOrderDialog` | dialog | `RxNet.kt:1276` | অর্ডার নিন | 1 |
| 115 | `OnlineSettingsDialog` | dialog | `RxNet.kt:1297` | অনলাইনে বিক্রি | 1 |
| 116 | `MissedDialog` | dialog | `RxNet.kt:1345` | চেয়েও পাননি এমন ওষুধ | 1 |
| 117 | `DispenseDialog` | dialog | `RxNet.kt:1380` | ওষুধ দিন | 1 |
| 118 | `StockDialog` | dialog | `RxNet.kt:1462` | স্টকের তালিকা | 1 |
| 119 | `RxSentDialog` | dialog | `RxNet.kt:1561` | প্রেসক্রিপশন পাঠানো হয়েছে | 2 |
| 120 | `SendErrorBar` | bar | `RxNet.kt:1610` |  | 2 |
| 121 | `RxPickDialog` | dialog | `RxNet.kt:1654` | কোন প্রেসক্রিপশন পাঠাবেন? | 1 |
| 122 | `PharmacySendDialog` | dialog | `RxNet.kt:1706` | এই ফার্মেসি এখন অনলাইনে অর্ডার নেয় না | 1 |
| 123 | `LabInfoSheet` | sheet | `RxNet.kt:1891` | বাসা থেকে নমুনা নেয় | 1 |
| 124 | `LabEditScreen` | screen | `RxNet.kt:1928` | আমার প্যাথলজি যোগ করুন | 1 |
| 125 | `LabReportEntryDialog` | dialog | `RxNet.kt:2022` | রিপোর্টের ছবি ১ | 1 |
| 126 | `ReviewReadDialog` | dialog | `RxNet.kt:2220` | রোগীকে কী জানাবেন? | 1 |
| 127 | `ReviewSettingsDialog` | dialog | `RxNet.kt:2253` | অনলাইনে রিপোর্ট দেখা | 1 |
| 128 | `RxReviewsScreen` | screen | `RxNet.kt:2301` | রিপোর্ট | 1 |
| 129 | `LoginScreen` | screen | `Screens.kt:61` | সঠিক ইমেইল দিন | 2 |
| 130 | `ProfileEditScreen` | screen | `Screens.kt:199` | আপনার প্রোফাইল | 3 |
| 131 | `HealthTab` | tab | `Services.kt:20` | আলোচনা | 1 |
| 132 | `ServicesTab` | tab | `Services.kt:49` | অ্যাম্বুলেন্স | 1 |
| 133 | `SettingsScreen` | screen | `Settings.kt:44` | সেভ হয়নি, ইন্টারনেট দেখুন। | 1 |
| 134 | `HelpScreen` | screen | `Support.kt:114` | সাহায্য ও আপিল | 1 |
| 135 | `UpdateBanner` | banner | `Updates.kt:84` | নতুন ভার্সন এসেছে | 1 |
| 136 | `PhoneVerifyDialog` | dialog | `Updates.kt:146` | ফোন নম্বর যাচাই | 1 |
| 137 | `WorkTab` | tab | `Work.kt:190` | আমার কাজ | 1 |
| 138 | `MyBusinessScreen` | screen | `Work.kt:272` |  | 1 |
| 139 | `AddBusinessDialog` | dialog | `Work.kt:295` | আপনার কোন ব্যবসা? | 1 |

### ইনলাইন ডায়ালগ/শিট (অন্য স্ক্রিনের ভেতরে)

| # | র‍্যাপার | কোন ফাংশনের ভেতরে | ফাইল:লাইন | শিরোনাম |
|---|---|---|---|---|
| 1 | AppBottomSheet | `TicketSheet` | `AdminMobile.kt:359` |  |
| 2 | AppBottomSheet | `EmergencyCreateSheet` | `AdminMobile.kt:510` | জরুরি আবেদন |
| 3 | AlertDialog | `open` | `AdminMobile.kt:688` | চেম্বার আর বাসায় সেবা তালিকা থেকে সরবে, সামনের অ্যাপয়েন্টমেন্ট বাতিল হবে। |
| 4 | AlertDialog | `hit` | `AmbFare.kt:220` | খুঁজুন |
| 5 | FormDialog | `StarRatingDialog` | `Ambulance.kt:598` |  |
| 6 | FormDialog | `ReasonReportDialog` | `Ambulance.kt:641` | সমস্যা জানান |
| 7 | FormDialog | `AmbulanceEditDialog` | `Ambulance.kt:811` | অ্যাম্বুলেন্স যোগ |
| 8 | FormDialog | `AmbulanceRequestDialog` | `AmbulanceRequest.kt:487` | অ্যাম্বুলেন্স ডাকুন |
| 9 | FormDialog | `ReasonDialog` | `AmbulanceRequest.kt:640` |  |
| 10 | FormDialog | `go` | `AmbulanceRequest.kt:675` | হ্যাঁ —  |
| 11 | DetailSheet | `ProviderDetailDialog` | `Appointment.kt:472` |  |
| 12 | AlertDialog | `ProviderDetailDialog` | `Appointment.kt:516` | কোন চেম্বারে? |
| 13 | FormDialog | `BookDialog` | `Appointment.kt:651` |  |
| 14 | FormDialog | `act` | `Appointment.kt:865` | অ্যাপয়েন্টমেন্ট নিশ্চিত |
| 15 | CardDialog | `None` | `AreaPicker.kt:60` | খুঁজুন |
| 16 | CardDialog | `HospitalPickerDialog` | `AreaPicker.kt:214` | হাসপাতাল বেছে নিন |
| 17 | FormDialog | `AddHospitalDialog` | `AreaPicker.kt:311` | হাসপাতাল যোগের অনুরোধ |
| 18 | AlertDialog | `AddHospitalDialog` | `AreaPicker.kt:419` | তালিকায় আগে থেকেই আছে |
| 19 | CardDialog | `FormDialog` | `AreaPicker.kt:487` |  |
| 20 | FormDialog | `BloodBankEditDialog` | `BloodBank.kt:343` | ব্লাড ব্যাংক যোগ |
| 21 | DetailSheet | `DirectoryCard` | `Cards.kt:131` |  |
| 22 | FormDialog | `act` | `CareAccess.kt:282` | নিশ্চিত? |
| 23 | FormDialog | `InviteDialog` | `CareAccess.kt:298` | কেয়ারগিভার যোগ করুন |
| 24 | FormDialog | `MemberDialog` | `CareAccess.kt:336` | পরিবারের সদস্য |
| 25 | ModalBottomSheet | `AppBottomSheet` | `Common.kt:298` |  |
| 26 | AlertDialog | `QuestionDetailScreen` | `Community.kt:669` | উত্তরটা মুছবেন? |
| 27 | AlertDialog | `QuestionDetailScreen` | `Community.kt:688` | প্রশ্নটা মুছবেন? |
| 28 | AlertDialog | `TipDetailScreen` | `Community.kt:878` | টিপসটা মুছবেন? |
| 29 | FormDialog | `BadgeApplyDialog` | `Community.kt:963` | ব্যাজের তথ্য সংশোধন |
| 30 | AlertDialog | `ReportDialog` | `Community.kt:1068` | রিপোর্ট করবেন? |
| 31 | FormDialog | `EditTextDialog` | `Community.kt:1097` | শিরোনাম |
| 32 | FormDialog | `DonationDateDialog` | `Corrections.kt:73` | শেষ রক্তদানের তারিখ |
| 33 | AlertDialog | `DemoSwitchDialog` | `DemoLogin.kt:124` | রোল বদলান |
| 34 | CardDialog | `hit` | `FacilityPicker.kt:148` | হাসপাতাল, ক্লিনিক বা জায়গার নাম |
| 35 | AppBottomSheet | `FilterSheet` | `FilterBar.kt:146` |  |
| 36 | FullScreenDialog | `pick` | `FilterBar.kt:207` | ফিরে যান |
| 37 | FormDialog | `LabSendDialog` | `LabPrice.kt:182` | $title — সময় বদলান |
| 38 | FormDialog | `HomeConfirmDialog` | `LabPrice.kt:243` | সময় নিশ্চিত করুন |
| 39 | FormDialog | `HomeDeclineDialog` | `LabPrice.kt:283` | বাসায় যেতে পারবেন না? |
| 40 | FormDialog | `HomeCancelDialog` | `LabPrice.kt:313` | বাসা থেকে নমুনা লাগবে না? |
| 41 | AlertDialog | `fromLink` | `LocationInput.kt:247` | এখনকার জায়গা দেবেন? |
| 42 | FullScreenDialog | `saveProfile` | `MainActivity.kt:870` |  |
| 43 | FormDialog | `MedAlarmDialog` | `MedAlarms.kt:221` | ওষুধের অ্যালার্ম |
| 44 | AlertDialog | `DonorRow` | `MyRequestUi.kt:199` | ${d.donorName}-কে বাদ দেবেন? |
| 45 | AlertDialog | `setReceived` | `MyRequestUi.kt:263` | রক্ত আর লাগবে না? |
| 46 | AlertDialog | `RxCodeDialog` | `MyWorkTabs.kt:164` | প্রেসক্রিপশন খুলুন |
| 47 | AlertDialog | `MyLabPanel` | `MyWorkTabs.kt:458` | প্যাথলজি মুছে ফেলবেন? |
| 48 | AlertDialog | `act` | `Pharmacy.kt:337` | কত দিন বন্ধ থাকবে? |
| 49 | AlertDialog | `act` | `Pharmacy.kt:357` | ফার্মেসি মুছে ফেলবেন? |
| 50 | AlertDialog | `RxSelector` | `Pharmacy.kt:640` | কোন প্রেসক্রিপশন? |
| 51 | DetailSheet | `NearbyPharmacies` | `Pharmacy.kt:794` | খোলা |
| 52 | FormDialog | `PharmacyRatingDialog` | `Pharmacy.kt:915` | দোকানের সেবা নিয়ে — ওষুধ কেমন কাজ করল তা নয় |
| 53 | FullScreenDialog | `info` | `Prescriptions.kt:401` | প্রেসক্রিপশন |
| 54 | AlertDialog | `info` | `Prescriptions.kt:481` | প্রেসক্রিপশনটা বাতিল করবেন? |
| 55 | FormDialog | `RxClaimDialog` | `Prescriptions.kt:604` | প্রেসক্রিপশন যোগ করুন |
| 56 | FullScreenDialog | `PatientHistoryDialog` | `Prescriptions.kt:795` | রোগী |
| 57 | FormDialog | `RxPatientAddDialog` | `Prescriptions.kt:834` | নতুন রোগী |
| 58 | FullScreenDialog | `RxDoctorTab` | `Prescriptions.kt:919` | রোগী বাছুন |
| 59 | FormDialog | `RxTeamDialog` | `Prescriptions.kt:951` | সহকারী |
| 60 | FullScreenDialog | `issue` | `Prescriptions.kt:1055` | প্রেসক্রিপশন |
| 61 | AlertDialog | `issue` | `Prescriptions.kt:1155` | প্রেসক্রিপশনটা দেবেন? |
| 62 | FormDialog | `MedEditDialog` | `Prescriptions.kt:1187` | ওষুধ যোগ করুন |
| 63 | AlertDialog | `pickPhoto` | `Profile.kt:344` | প্রোফাইলের ছবি |
| 64 | AlertDialog | `pickPhoto` | `Profile.kt:368` | অ্যাকাউন্ট মুছবেন? |
| 65 | FormDialog | `LateDialog` | `Queue.kt:249` | দেরি হবে |
| 66 | AlertDialog | `act` | `RequestScreens.kt:611` | রিপোর্ট করবেন? |
| 67 | AlertDialog | `act` | `RequestScreens.kt:802` | রিকোয়েস্টটা মুছে ফেলবেন? |
| 68 | AlertDialog | `DonationChecklistDialog` | `RequestScreens.kt:863` | রক্ত দেওয়ার আগে |
| 69 | AlertDialog | `info` | `RxNet.kt:667` | ফার্মেসিতে পাঠান |
| 70 | FullScreenDialog | `send` | `RxNet.kt:832` | অনলাইনে অর্ডার |
| 71 | FormDialog | `OnlineOrderDialog` | `RxNet.kt:924` | ডেলিভারি:  |
| 72 | FormDialog | `WithdrawDialog` | `RxNet.kt:966` | ${o.pharmacyName} থেকে ফিরিয়ে নেবেন? |
| 73 | FullScreenDialog | `send` | `RxNet.kt:1035` | প্যাথলজি বাছুন |
| 74 | FullScreenDialog | `LabReportDialog` | `RxNet.kt:1108` | পরীক্ষার রিপোর্ট |
| 75 | FormDialog | `PayDialog` | `RxNet.kt:1243` | রিপোর্ট দেখানোর টাকা |
| 76 | FormDialog | `AcceptOrderDialog` | `RxNet.kt:1279` | অর্ডার নিন |
| 77 | FormDialog | `OnlineSettingsDialog` | `RxNet.kt:1305` | অনলাইনে বিক্রি |
| 78 | FullScreenDialog | `MissedDialog` | `RxNet.kt:1349` | চেয়েও পাননি এমন ওষুধ |
| 79 | FullScreenDialog | `DispenseDialog` | `RxNet.kt:1389` | ওষুধ দিন |
| 80 | FullScreenDialog | `change` | `RxNet.kt:1478` | স্টকের তালিকা |
| 81 | AlertDialog | `RxSentDialog` | `RxNet.kt:1563` | প্রেসক্রিপশন পাঠানো হয়েছে |
| 82 | FullScreenDialog | `RxPickDialog` | `RxNet.kt:1668` | কোন প্রেসক্রিপশন পাঠাবেন? |
| 83 | AlertDialog | `PharmacySendDialog` | `RxNet.kt:1733` | এই ফার্মেসি এখন অনলাইনে অর্ডার নেয় না |
| 84 | AlertDialog | `PharmacySendDialog` | `RxNet.kt:1747` | ডাক্তার |
| 85 | AlertDialog | `PharmacySendDialog` | `RxNet.kt:1770` |  |
| 86 | DetailSheet | `LabInfoSheet` | `RxNet.kt:1901` | বাসা থেকে নমুনা নেয় |
| 87 | FullScreenDialog | `flagOf` | `RxNet.kt:2058` | রিপোর্ট |
| 88 | FullScreenDialog | `ReviewReadDialog` | `RxNet.kt:2227` | রোগীকে কী জানাবেন? |
| 89 | FormDialog | `ReviewSettingsDialog` | `RxNet.kt:2259` | অনলাইনে রিপোর্ট দেখা |
| 90 | DatePickerDialog | `isSelectableDate` | `Screens.kt:479` | ঠিক আছে |
| 91 | FormDialog | `send` | `Updates.kt:162` | ফোন নম্বর যাচাই |
| 92 | AlertDialog | `AddBusinessDialog` | `Work.kt:296` | আপনার কোন ব্যবসা? |

## ঘ) ওয়েব (Vue) — রুট ও পাতা

টপ-লেভেল রুট (`router.ts`): `/`, `/request`, `/my-requests`, `/services`, `/appointment`, `/my-appointments`, `/health`, `/prescriptions`, `/work`, `/profile`, `/settings`, `/inbox`, `/help`, `/care`, `/login`, `/:pathMatch(.*)*`

কোডে পাওয়া সব পাথ (লিংক/নেভিগেশন): `/appointment`, `/appointment/doctor/`, `/appointment/doctor/:id`, `/appointment/home-care`, `/appointment/home-care/`, `/appointment/home-care/:id`, `/care`, `/health`, `/health/`, `/health/ask`, `/health/badge`, `/health/discussion`, `/health/post-tip`, `/health/question/`, `/health/question/:id`, `/health/tip/`, `/health/tip/:id`, `/health/tips`, `/help`, `/help/`, `/help/:id`, `/help/new`, `/inbox`, `/inbox/`, `/inbox/:id`, `/login`, `/my-appointments`, `/my-appointments/`, `/my-appointments/:id`, `/my-requests`, `/my-requests/`, `/my-requests/:id`, `/my-requests/:id/edit`, `/prescriptions`, `/prescriptions/`, `/prescriptions/:id`, `/profile`, `/profile/edit`, `/request`, `/request/`, `/request/:id`, `/request/new`, `/services`, `/services/`, `/services/:kind`, `/services/:kind/:id`, `/services/ambulance`, `/services/ambulance/call/`, `/services/ambulance/call/:id`, `/services/bloodbank`, `/services/pharmacy`, `/services/pharmacy/medicine`, `/settings`, `/work`, `/work/`, `/work/add`, `/work/ambulance`, `/work/ambulance/`, `/work/ambulance/:id`, `/work/ambulance/new`, `/work/bloodbank`, `/work/bloodbank/`, `/work/bloodbank/:id`, `/work/bloodbank/new`, `/work/lab`, `/work/lab/edit`, `/work/lab/info`, `/work/lab/new`, `/work/lab/orders`, `/work/lab/orders/`, `/work/lab/orders/*`, `/work/lab/orders/prices`, `/work/lab/orders/ranges`, `/work/pharmacy`, `/work/pharmacy/edit`, `/work/pharmacy/finds`, `/work/pharmacy/new`, `/work/pharmacy/orders`, `/work/pharmacy/orders/*`, `/work/pharmacy/shop`, `/work/pharmacy/shop/`, `/work/pharmacy/shop/:sub`, `/work/pro`, `/work/pro/*`, `/work/rx`, `/work/rx/*`

শেয়ার/যাচাইয়ের পাতা সার্ভার বানায় (Spring `ShareController`): `/r/{id}`, `/rx/{code}`, `/shop/{code}`, `/api/share?rxqr=|shopqr=` (QR SVG), `/.well-known/assetlinks.json`; ডাউনলোড `/download/` (nginx)।

| # | কম্পোনেন্ট | ধরন | ফাইল |
|---|---|---|---|
| 1 | `ProfileForm` | form | `web-vue/src/account/ProfileForm.vue` |
| 2 | `DemoSwitchDialog` | dialog | `web-vue/src/demo/DemoSwitchDialog.vue` |
| 3 | `AccountSection` | section | `web-vue/src/sections/account/AccountSection.vue` |
| 4 | `CarePage` | page | `web-vue/src/sections/account/CarePage.vue` |
| 5 | `DonationDateDialog` | dialog | `web-vue/src/sections/account/DonationDateDialog.vue` |
| 6 | `EditProfilePage` | page | `web-vue/src/sections/account/EditProfilePage.vue` |
| 7 | `HelpPage` | page | `web-vue/src/sections/account/HelpPage.vue` |
| 8 | `InboxPage` | page | `web-vue/src/sections/account/InboxPage.vue` |
| 9 | `InviteDialog` | dialog | `web-vue/src/sections/account/InviteDialog.vue` |
| 10 | `LoginPage` | page | `web-vue/src/sections/account/LoginPage.vue` |
| 11 | `MemberDialog` | dialog | `web-vue/src/sections/account/MemberDialog.vue` |
| 12 | `PhoneVerifyDialog` | dialog | `web-vue/src/sections/account/PhoneVerifyDialog.vue` |
| 13 | `PhotoMenuDialog` | dialog | `web-vue/src/sections/account/PhotoMenuDialog.vue` |
| 14 | `ProfilePage` | page | `web-vue/src/sections/account/ProfilePage.vue` |
| 15 | `SettingsPage` | page | `web-vue/src/sections/account/SettingsPage.vue` |
| 16 | `AddHospitalDialog` | dialog | `web-vue/src/sections/appointment/AddHospitalDialog.vue` |
| 17 | `AppointmentSection` | section | `web-vue/src/sections/appointment/AppointmentSection.vue` |
| 18 | `AreaSheet` | sheet | `web-vue/src/sections/appointment/AreaSheet.vue` |
| 19 | `BookDialog` | dialog | `web-vue/src/sections/appointment/BookDialog.vue` |
| 20 | `ChamberPicker` | picker | `web-vue/src/sections/appointment/ChamberPicker.vue` |
| 21 | `DoctorsPage` | page | `web-vue/src/sections/appointment/DoctorsPage.vue` |
| 22 | `FilterSheet` | sheet | `web-vue/src/sections/appointment/FilterSheet.vue` |
| 23 | `HomeCarePage` | page | `web-vue/src/sections/appointment/HomeCarePage.vue` |
| 24 | `HospitalPickerDialog` | dialog | `web-vue/src/sections/appointment/HospitalPickerDialog.vue` |
| 25 | `LateDialog` | dialog | `web-vue/src/sections/appointment/LateDialog.vue` |
| 26 | `MyAppointmentsPage` | page | `web-vue/src/sections/appointment/MyAppointmentsPage.vue` |
| 27 | `PhoneVerifyDialog` | dialog | `web-vue/src/sections/appointment/PhoneVerifyDialog.vue` |
| 28 | `QueuePanel` | panel | `web-vue/src/sections/appointment/QueuePanel.vue` |
| 29 | `ReasonReportDialog` | dialog | `web-vue/src/sections/appointment/ReasonReportDialog.vue` |
| 30 | `SpecialtyPicker` | picker | `web-vue/src/sections/appointment/SpecialtyPicker.vue` |
| 31 | `StarRatingDialog` | dialog | `web-vue/src/sections/appointment/StarRatingDialog.vue` |
| 32 | `BookingsTab` | tab | `web-vue/src/sections/appointment/pro/BookingsTab.vue` |
| 33 | `ConfirmApptDialog` | dialog | `web-vue/src/sections/appointment/pro/ConfirmApptDialog.vue` |
| 34 | `HomeCareTab` | tab | `web-vue/src/sections/appointment/pro/HomeCareTab.vue` |
| 35 | `PatientsTab` | tab | `web-vue/src/sections/appointment/pro/PatientsTab.vue` |
| 36 | `UpazilaPicker` | picker | `web-vue/src/sections/appointment/pro/UpazilaPicker.vue` |
| 37 | `BloodSection` | section | `web-vue/src/sections/blood/BloodSection.vue` |
| 38 | `DonationChecklistDialog` | dialog | `web-vue/src/sections/blood/DonationChecklistDialog.vue` |
| 39 | `MyRequestSection` | section | `web-vue/src/sections/blood/MyRequestSection.vue` |
| 40 | `MyRequestsPage` | page | `web-vue/src/sections/blood/MyRequestsPage.vue` |
| 41 | `AddHospitalDialog` | dialog | `web-vue/src/sections/blood/form/AddHospitalDialog.vue` |
| 42 | `BagsPicker` | picker | `web-vue/src/sections/blood/form/BagsPicker.vue` |
| 43 | `EditRequestForm` | form | `web-vue/src/sections/blood/form/EditRequestForm.vue` |
| 44 | `EditRequestPage` | page | `web-vue/src/sections/blood/form/EditRequestPage.vue` |
| 45 | `NewRequestForm` | form | `web-vue/src/sections/blood/form/NewRequestForm.vue` |
| 46 | `NewRequestPage` | page | `web-vue/src/sections/blood/form/NewRequestPage.vue` |
| 47 | `CloseRequestDialog` | dialog | `web-vue/src/sections/blood/parts/CloseRequestDialog.vue` |
| 48 | `ShareDialog` | dialog | `web-vue/src/sections/blood/parts/ShareDialog.vue` |
| 49 | `AskPage` | page | `web-vue/src/sections/health/AskPage.vue` |
| 50 | `BadgePage` | page | `web-vue/src/sections/health/BadgePage.vue` |
| 51 | `DiscussionPage` | page | `web-vue/src/sections/health/DiscussionPage.vue` |
| 52 | `EditTextDialog` | dialog | `web-vue/src/sections/health/EditTextDialog.vue` |
| 53 | `HealthReportDialog` | dialog | `web-vue/src/sections/health/HealthReportDialog.vue` |
| 54 | `HealthSection` | section | `web-vue/src/sections/health/HealthSection.vue` |
| 55 | `HealthTabs` | tab | `web-vue/src/sections/health/HealthTabs.vue` |
| 56 | `LabPickDialog` | dialog | `web-vue/src/sections/health/LabPickDialog.vue` |
| 57 | `LabReportDialog` | dialog | `web-vue/src/sections/health/LabReportDialog.vue` |
| 58 | `LabSendDialog` | dialog | `web-vue/src/sections/health/LabSendDialog.vue` |
| 59 | `OnlineOrderDialog` | dialog | `web-vue/src/sections/health/OnlineOrderDialog.vue` |
| 60 | `PayDialog` | dialog | `web-vue/src/sections/health/PayDialog.vue` |
| 61 | `PharmacyPickDialog` | dialog | `web-vue/src/sections/health/PharmacyPickDialog.vue` |
| 62 | `PharmacySendDialog` | dialog | `web-vue/src/sections/health/PharmacySendDialog.vue` |
| 63 | `PostTipPage` | page | `web-vue/src/sections/health/PostTipPage.vue` |
| 64 | `PrescriptionsPage` | page | `web-vue/src/sections/health/PrescriptionsPage.vue` |
| 65 | `RxActivitySection` | section | `web-vue/src/sections/health/RxActivitySection.vue` |
| 66 | `RxClaimDialog` | dialog | `web-vue/src/sections/health/RxClaimDialog.vue` |
| 67 | `RxPickDialog` | dialog | `web-vue/src/sections/health/RxPickDialog.vue` |
| 68 | `RxSentDialog` | dialog | `web-vue/src/sections/health/RxSentDialog.vue` |
| 69 | `TipsPage` | page | `web-vue/src/sections/health/TipsPage.vue` |
| 70 | `WithdrawDialog` | dialog | `web-vue/src/sections/health/WithdrawDialog.vue` |
| 71 | `DoctorReportDialog` | dialog | `web-vue/src/sections/rx/doctor/DoctorReportDialog.vue` |
| 72 | `PatientAddDialog` | dialog | `web-vue/src/sections/rx/doctor/PatientAddDialog.vue` |
| 73 | `PatientsPage` | page | `web-vue/src/sections/rx/doctor/PatientsPage.vue` |
| 74 | `ReviewReadDialog` | dialog | `web-vue/src/sections/rx/doctor/ReviewReadDialog.vue` |
| 75 | `ReviewSettingsDialog` | dialog | `web-vue/src/sections/rx/doctor/ReviewSettingsDialog.vue` |
| 76 | `ReviewsPage` | page | `web-vue/src/sections/rx/doctor/ReviewsPage.vue` |
| 77 | `RxListPage` | page | `web-vue/src/sections/rx/doctor/RxListPage.vue` |
| 78 | `RxViewPage` | page | `web-vue/src/sections/rx/doctor/RxViewPage.vue` |
| 79 | `TeamPage` | page | `web-vue/src/sections/rx/doctor/TeamPage.vue` |
| 80 | `WritePage` | page | `web-vue/src/sections/rx/doctor/WritePage.vue` |
| 81 | `AcceptDialog` | dialog | `web-vue/src/sections/rx/partner/AcceptDialog.vue` |
| 82 | `CancelOrderDialog` | dialog | `web-vue/src/sections/rx/partner/CancelOrderDialog.vue` |
| 83 | `DispenseForm` | form | `web-vue/src/sections/rx/partner/DispenseForm.vue` |
| 84 | `HomeConfirmDialog` | dialog | `web-vue/src/sections/rx/partner/HomeConfirmDialog.vue` |
| 85 | `MissedPanel` | panel | `web-vue/src/sections/rx/partner/MissedPanel.vue` |
| 86 | `OnlineForm` | form | `web-vue/src/sections/rx/partner/OnlineForm.vue` |
| 87 | `OnlineSettingsPanel` | panel | `web-vue/src/sections/rx/partner/OnlineSettingsPanel.vue` |
| 88 | `OrdersTab` | tab | `web-vue/src/sections/rx/partner/OrdersTab.vue` |
| 89 | `Panel` | panel | `web-vue/src/sections/rx/partner/Panel.vue` |
| 90 | `PartnerPage` | page | `web-vue/src/sections/rx/partner/PartnerPage.vue` |
| 91 | `PriceListPanel` | panel | `web-vue/src/sections/rx/partner/PriceListPanel.vue` |
| 92 | `PriceSettingsPanel` | panel | `web-vue/src/sections/rx/partner/PriceSettingsPanel.vue` |
| 93 | `RxPanel` | panel | `web-vue/src/sections/rx/partner/RxPanel.vue` |
| 94 | `ShopCodePanel` | panel | `web-vue/src/sections/rx/partner/ShopCodePanel.vue` |
| 95 | `AddPanel` | panel | `web-vue/src/sections/rx/partner/books/AddPanel.vue` |
| 96 | `AdjustDialog` | dialog | `web-vue/src/sections/rx/partner/books/AdjustDialog.vue` |
| 97 | `BulkPanel` | panel | `web-vue/src/sections/rx/partner/books/BulkPanel.vue` |
| 98 | `BuyTab` | tab | `web-vue/src/sections/rx/partner/books/BuyTab.vue` |
| 99 | `ItemDialog` | dialog | `web-vue/src/sections/rx/partner/books/ItemDialog.vue` |
| 100 | `ItemPicker` | picker | `web-vue/src/sections/rx/partner/books/ItemPicker.vue` |
| 101 | `ItemsTab` | tab | `web-vue/src/sections/rx/partner/books/ItemsTab.vue` |
| 102 | `ReportTab` | tab | `web-vue/src/sections/rx/partner/books/ReportTab.vue` |
| 103 | `SalesTab` | tab | `web-vue/src/sections/rx/partner/books/SalesTab.vue` |
| 104 | `ActiveBanner` | banner | `web-vue/src/sections/services/ActiveBanner.vue` |
| 105 | `BuyModePicker` | picker | `web-vue/src/sections/services/BuyModePicker.vue` |
| 106 | `CallBar` | bar | `web-vue/src/sections/services/CallBar.vue` |
| 107 | `DestPicker` | picker | `web-vue/src/sections/services/DestPicker.vue` |
| 108 | `FacilityDialog` | dialog | `web-vue/src/sections/services/FacilityDialog.vue` |
| 109 | `FilterSheet` | sheet | `web-vue/src/sections/services/FilterSheet.vue` |
| 110 | `PickCarDialog` | dialog | `web-vue/src/sections/services/PickCarDialog.vue` |
| 111 | `RatingDialog` | dialog | `web-vue/src/sections/services/RatingDialog.vue` |
| 112 | `ReasonDialog` | dialog | `web-vue/src/sections/services/ReasonDialog.vue` |
| 113 | `ReportDialog` | dialog | `web-vue/src/sections/services/ReportDialog.vue` |
| 114 | `RequestDialog` | dialog | `web-vue/src/sections/services/RequestDialog.vue` |
| 115 | `RequestPage` | page | `web-vue/src/sections/services/RequestPage.vue` |
| 116 | `ServiceTabs` | tab | `web-vue/src/sections/services/ServiceTabs.vue` |
| 117 | `ServicesSection` | section | `web-vue/src/sections/services/ServicesSection.vue` |
| 118 | `AmbulanceForm` | form | `web-vue/src/sections/work/AmbulanceForm.vue` |
| 119 | `BizPage` | page | `web-vue/src/sections/work/BizPage.vue` |
| 120 | `BloodBankForm` | form | `web-vue/src/sections/work/BloodBankForm.vue` |
| 121 | `LabForm` | form | `web-vue/src/sections/work/LabForm.vue` |
| 122 | `LabHospitalPicker` | picker | `web-vue/src/sections/work/LabHospitalPicker.vue` |
| 123 | `LabTabs` | tab | `web-vue/src/sections/work/LabTabs.vue` |
| 124 | `PharmacyForm` | form | `web-vue/src/sections/work/PharmacyForm.vue` |
| 125 | `PharmacyTabs` | tab | `web-vue/src/sections/work/PharmacyTabs.vue` |
| 126 | `StatsDialog` | dialog | `web-vue/src/sections/work/StatsDialog.vue` |
| 127 | `WorkSection` | section | `web-vue/src/sections/work/WorkSection.vue` |
| 128 | `FormPage` | page | `web-vue/src/shell/FormPage.vue` |
| 129 | `SubPageBar` | bar | `web-vue/src/shell/SubPageBar.vue` |
| 130 | `AreaPicker` | picker | `web-vue/src/ui/AreaPicker.vue` |
| 131 | `ConfirmDialog` | dialog | `web-vue/src/ui/ConfirmDialog.vue` |
| 132 | `Dialog` | dialog | `web-vue/src/ui/Dialog.vue` |
| 133 | `FilterBar` | bar | `web-vue/src/ui/FilterBar.vue` |
| 134 | `PillTabs` | tab | `web-vue/src/ui/PillTabs.vue` |
| 135 | `PlacePicker` | picker | `web-vue/src/ui/PlacePicker.vue` |

(বাকি 230টা `.vue` ফাইল কার্ড/অংশ — `tools/data`-তে নেই, `web-vue/src`-এ দেখুন।)

### অ্যাডমিন প্যানেল (`/admin/#/…`)

| পাথ | নাম |
|---|---|
| `#/` | ড্যাশবোর্ড |
| `#/analytics` | অ্যানালিটিক্স |
| `#/emergency` | জরুরি আহ্বান |
| `#/badges` | ব্যাজ |
| `#/pharmacies` | ফার্মেসি |
| `#/ambulances` | অ্যাম্বুলেন্স |
| `#/bloodBanks` | ব্লাড ব্যাংক |
| `#/labs` | প্যাথলজি |
| `#/hospitals` | হাসপাতাল |
| `#/tickets` | আপিল ও সমস্যা |
| `#/reports` | রিপোর্ট |
| `#/corrections` | তারিখ সংশোধন |
| `#/users` | ব্যবহারকারী |
| `#/catalog` | বাছাইয়ের তালিকা |
| `#/labtests` | রিপোর্টের ছক |
| `#/log` | কাজের ইতিহাস |
| `#/admins` | অ্যাডমিন |
| `#/demo` | ডেমো (পরীক্ষা) |
| `#/verify/…`, `#/ticket/…`, `#/user/…` | যাচাই (কাগজের ছবি), টিকিট, ব্যবহারকারীর বিস্তারিত |

## ঙ) `/api/community` action — ২২৫টা

auth = `API-INVENTORY.md`-এর লেখা; markers = Kotlin হ্যান্ডলারে যা পাওয়া গেছে (admin, owner, doctor, doctor|assistant, badge, pharmacy, lab, own-row, same-world, quota); clients = কে পাঠায় (app/web/admin)।


### package `admin`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `admin_add`  | Add admin email (≤20); clears admin cache | owner web-panel | owner | admin | `backend/src/main/kotlin/com/lifolo/admin/AdminPanelActions.kt:65` |
| `admin_catalog_set`  | Save one pick-list (order, names, hidden, new codes ≤80) | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/AdminPanelActions.kt:164` |
| `admin_list`  | List owners (env) and extra admins (config/admins) | owner (ADMIN_EMAILS, verified email) web-panel | owner | — | `backend/src/main/kotlin/com/lifolo/admin/AdminPanelActions.kt:59` |
| `admin_log`  | Admin action history, 100/page, cursor `before` (at ms) | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/AdminWebActions.kt:446` |
| `admin_overview`  | Web panel landing: badges, pharmacies, ambulances, blood banks, labs, hospital requests, reports, corrections, open tickets, admins (each pa | user admin web-panel | admin, owner | app,admin | `backend/src/main/kotlin/com/lifolo/admin/AdminPanelActions.kt:119` |
| `admin_remove`  | Remove admin email; clears admin cache | owner web-panel | owner | admin | `backend/src/main/kotlin/com/lifolo/admin/AdminPanelActions.kt:76` |
| `admin_report_resolve`  | Dismiss or remove target (delete post / suspend listing / block home care) + sanctions; pushes owner | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/admin/ModerationActions.kt:227` |
| `admin_reports`  | Open reports grouped by target (provider reports only with panel:true) | user admin web-panel | admin | — | `backend/src/main/kotlin/com/lifolo/admin/ModerationActions.kt:165` |
| `admin_request_docs`  | Ask owner for document photos (note); pushes owner | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/VerifyActions.kt:110` |
| `admin_stats`  | Analytics (users, requests, listings…), cached 30 min in serverState/_adminStats; fresh:true recomputes (count() queries) | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/AdminWebActions.kt:87` |
| `admin_ticket`  | One ticket with user info | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/SupportActions.kt:151` |
| `admin_ticket_reply`  | Reply and/or close; pushes user | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/admin/SupportActions.kt:160` |
| `admin_tickets`  | Tickets by status (open default) | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/SupportActions.kt:141` |
| `admin_user`  | Full user view: profile, auth, badge, listings, sanctions, counts | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/admin/AdminWebActions.kt:274` |
| `admin_user_action`  | Disable/enable account (Auth + revoke + disabledReason), lift/impose sanctions, home-care block; pushes user | admin (owner for actions on admins/owner) web-panel | admin, owner, own-row | app,admin | `backend/src/main/kotlin/com/lifolo/admin/AdminWebActions.kt:372` |
| `admin_user_find`  | Find users by email/phone/uid (Auth lookup + users/pharmacies/ambulances phone variants) | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/admin/AdminWebActions.kt:230` |
| `admin_user_section`  | Read-only section of a user’s activity (blood, appointments, rx, orders, …) | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/UserActivity.kt:37` |
| `admin_verify_docs`  | Document photos for verification panel | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/admin/VerifyActions.kt:80` |
| `catalog_get` GUEST | All pick-lists (specialties, home services, ambulance types, blood bank services) merged with defaults; config/catalog cached 5 min (also lo | guest web-panel | — | app,web,admin | `backend/src/main/kotlin/com/lifolo/admin/AdminPanelActions.kt` |
| `report`  | Report content/listing (question, answer, tip, provider, homecare, ambulance, pharmacy, bloodbank …) once per target; quota report=20/day; m | user | own-row, quota | app,web,admin | `backend/src/main/kotlin/com/lifolo/admin/ModerationActions.kt:91` |
| `ticket_create`  | Open support/appeal ticket (category, text); quota ticket=5/day | user | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/admin/SupportActions.kt:89` |
| `ticket_reply`  | Add message (reopens closed ticket) | user (ticket owner) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/admin/SupportActions.kt:125` |
| `tickets_mine`  | My last 20 tickets | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/admin/SupportActions.kt:119` |
| `verify_docs_upload`  | Owner uploads requested document photos (base64) for badge/pharmacy/ambulance/bloodbank/lab; quota docs=10/day | user (listing owner) | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/admin/VerifyActions.kt:59` |

### package `ambulance`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `admin_ambulance_resolve`  | listingreview.resolveListing: approve/reject/suspend/reinstate with checks + reason; sanctions ban/unban; push owner | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceModule.kt` |
| `admin_ambulances`  | Ambulances by status (pending default) with overchargeCount, docs | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceModule.kt:65` |
| `amb_facilities`  | Destination hospital list for district (cached 10 min) or search q / nearest to lat,lng | user | — | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceModule.kt:73` |
| `ambreq_accept`  | "Coming": quote mode stores fare offer (≤ budget, 2 min validity) else first-come accept; marks ambulance busy | user (driver the request was sent to) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:948` |
| `ambreq_agree`  | Patient accepts a quote → accepted, agreedFare; releases other quotes, pushes losers | user (patient) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1037` |
| `ambreq_budget`  | Raise max fare / includeUnknown; sends to ≤5 newly matching ambulances, resets 3-min expiry | user (patient) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1113` |
| `ambreq_byphone`  | Patient found a car by phone (one they called during this request) → close as byPhone, push others | user (patient) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1303` |
| `ambreq_cancel`  | Patient cancels with reason; frees ambulance, pushes driver(s) | user (patient) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:913` |
| `ambreq_count`  | Preview count of matching free ambulances for the fare/type/destination (no writes; district list cached 10 s) | user | — | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:833` |
| `ambreq_create`  | "Call an ambulance": pick candidates (≤130 km, available, type, fare filter), wave of 5, push drivers; one active request per patient; PER_H | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:742` |
| `ambreq_decline`  | Driver declines; widens early if everyone declined | user (driver) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1153` |
| `ambreq_driver`  | Driver inbox: open requests sent to me + the one I accepted (advances lazily) | user | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:922` |
| `ambreq_replace`  | Drop accepted driver (patient_found_other); with ambulanceId → byPhone close, else reopen without that driver | user (patient) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1312` |
| `ambreq_status`  | Patient waiting page: request by id or own active (ambReqLimits.activeId); advances timeouts lazily | user (patient of request) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:885` |
| `ambreq_step`  | Driver: arrived / done / cancel(reason); records trip on ambulanceCalls (agreedFare) for rating | user (accepted driver) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1208` |
| `ambulance_available`  | Toggle available (sets availableAt, remindAt = +11 h) | user (owner) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:401` |
| `ambulance_called`  | Record a call (ambulanceCalls uid_id, 10-min dedupe), bump ambulanceStats; attach to active ambreq when reqId given; same world only | user | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:443` |
| `ambulance_delete`  | Delete own ambulance (not when suspended or with open reports); deletes verify docs | user (owner) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:418` |
| `ambulance_mine`  | Own ambulances (≤ MAX_PER_OWNER) with docs status | user | — | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:284` |
| `ambulance_rate`  | 1–5 stars within 7 days of a call; overcharged flag only for in-app fare trips; updates ratingSum/Count + recent comments | user | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:479` |
| `ambulance_save`  | Add/edit ambulance (name, phone, vehicleNo, types, area, fare chart, base lat/lng, docs); re-verification on key changes; sanctions check; q | user | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:292` |
| `ambulance_stats`  | Owner stats per ambulance: calls per day, rating, recent comments, open report count | user | — | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:557` |
| `ambulances` GUEST | Approved ambulances in district: available first, nearest (gps), own upazila, rating; canRate per caller; busyCount, nearestId; kicks backgr | guest | own-row, same-world | app,web,admin | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:201` |
| `maps_link_resolve`  | Google Maps link/text → {lat,lng}; short links resolved by HTTP redirect (per-uid in-memory limit, 429) | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceModule.kt:88` |

### package `appointments`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `appointment_late` CARE | "I will be late" 10/20/30/60 min + note, once per day; pushes doctor/assistants | user (patient of appointment) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/appointments/QueueActions.kt:477` |
| `appointment_late_reply`  | Doctor/assistant replies ok / later (moves to end); pushes patient | user role:doctor(chamber)\|assistant | doctor|assistant | app,web | `backend/src/main/kotlin/com/lifolo/appointments/QueueActions.kt:509` |
| `appointment_queue` CARE | Live serial status, people ahead, ETA (queueStats average) | user (patient of appointment) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/appointments/QueueActions.kt:465` |
| `appointment_update` CARE | confirm (assigns serial via serials counter, transaction) / decline / cancel; pushes other party | user (provider: confirm/decline needs badge; patient or provider: cancel) caregiver | badge, own-row | app,web | `backend/src/main/kotlin/com/lifolo/appointments/AppointmentActions.kt:501` |
| `book` CARE | Patient books provider chamber date (≤60 days ahead, same world, listed+taking appointments, chamberKey check, member for family); quota boo | user role:badge caregiver | badge, own-row, same-world, quota | app,web | `backend/src/main/kotlin/com/lifolo/appointments/AppointmentActions.kt:361` |
| `my_appointments` CARE | Appointments as patient and as provider (active ≤300, history ≤30 per status); other party phone when confirmed | user caregiver | — | app,web | `backend/src/main/kotlin/com/lifolo/appointments/AppointmentActions.kt:449` |
| `provider_me`  | Own provider (chambers) document | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/appointments/AppointmentActions.kt:192` |
| `provider_save`  | Save chambers (≤ MAX_CHAMBERS, days/times, fee, appointments), specialties, degrees, gender, homeVisit; legacy shape supported; cancels stal | user role:badge (doctor/nurse/nutritionist/trainer); phone-verified when enabled | badge, own-row | app,web | `backend/src/main/kotlin/com/lifolo/appointments/AppointmentActions.kt:234` |
| `providers` GUEST CARE | Doctor/nurse chamber list with filters (district/upazila/union/hospital/specialty/day/openNow/maxFee/hospitalQ/gender) + facets; list cached | guest caregiver | — | app,web | `backend/src/main/kotlin/com/lifolo/appointments/AppointmentActions.kt:146` |
| `queue_control`  | start \\| next \\| set \\| pause \\| resume a chamber queue (expect for stale detection); pushes upcoming patients (near turn) | user role:doctor(chamber)\|assistant | doctor|assistant | app,web | `backend/src/main/kotlin/com/lifolo/appointments/QueueActions.kt:348` |
| `queue_today`  | Doctor/assistant: today’s chambers with confirmed serials, current position | user role:doctor(chamber)\|assistant | doctor|assistant | app,web | `backend/src/main/kotlin/com/lifolo/appointments/QueueActions.kt:311` |

### package `auth`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `web_logout`  | Clears the admin web-session cookie | web session cookie (any) web-panel | — | admin | `backend/src/main/kotlin/com/lifolo/auth/WebSession.kt:87` |

### package `blood`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `admin_bloodbank_add`  | Admin adds an ownerless approved blood bank | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:320` |
| `admin_bloodbank_delete`  | Admin deletes blood bank (+stats, docs, reports); pushes owner with reason | user admin web-panel | admin, own-row | admin | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:366` |
| `admin_bloodbank_edit`  | Admin edits any blood bank; pushes owner if any | user admin web-panel | admin, own-row | admin | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:335` |
| `admin_bloodbank_resolve`  | resolveListing for blood banks | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:307` |
| `admin_bloodbanks`  | Blood banks by status | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:283` |
| `admin_districts`  | District names (bn) sorted, for panel forms | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:313` |
| `admin_emergencies`  | Recent 30 emergencies with counts | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:491` |
| `admin_emergency_close`  | End emergency; push coming donors | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:479` |
| `admin_emergency_create`  | Create emergency call (groups/need, radius, hours, hospital, contact) and send first wave of pushes | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:400` |
| `admin_emergency_group`  | Close/reopen one blood group | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:462` |
| `admin_emergency_more`  | Send next wave to not-yet-notified eligible donors | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:454` |
| `blood_contact_get`  | Requester contact (requests/{id}/private/contact) | user (requester, or accepted/donated donor of open request) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:227` |
| `blood_request_get`  | One blood request (requestOut) or null | user (same world) | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:174` |
| `blood_requests_mine`  | Own requests, newest first | user | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:181` |
| `blood_requests_near`  | Open active requests within maxKm (geoOpen geohash bounds, fallback district list), group filter, same world, urgent first | user | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:189` |
| `blood_response_get`  | Own response to a request | user | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:209` |
| `blood_responses_list`  | Accepted/donated responses with donor name/phone | user (requester) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:215` |
| `bloodbank_called`  | Record call (10-min dedupe) + bloodBankStats | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:252` |
| `bloodbank_delete`  | Delete own blood bank (not suspended / open reports) + stats + verify docs | user (owner) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:232` |
| `bloodbank_mine`  | Own blood banks with docs status | user | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:184` |
| `bloodbank_save`  | Add/edit blood bank (terms, docs); re-verify on key changes; sanctions; quota bloodbank=5/day | user | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:188` |
| `bloodbank_stats`  | Owner stats per bank: calls/day, reports | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:269` |
| `bloodbanks` GUEST | Approved blood banks in district (open first, own upazila, govt first; open24 filter) | guest | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:156` |
| `confirm_donor`  | Requester confirms a donor; pushes donor | user (requester) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/DonorService.kt:170` |
| `donation_date_set`  | Change last donation date (backward needs confirmed:true → 409 confirm_needed); risk log; quota dateChange=3/day | user | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/blood/DonorService.kt:240` |
| `emergencies_active` GUEST | Active emergency calls for my district or that notified me (+ my status, ask list); guest: district only, no demo | guest | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:269` |
| `emergency_donated`  | Donor reports donated or not; sets lastDonation forward | user | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:307` |
| `emergency_respond`  | Donor coming/cancel; group compatibility, per-group counts | user | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:343` |
| `guest_requests` GUEST | Guest home: open requests near lat,lng (BD bounds, maxKm 5–25), no name/phone/note/location (cached per 0.05° cell 1 min) | guest | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:256` |
| `report_request`  | Report a blood request once (reports/{requestId}_{uid}); 409 if repeated | user | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:242` |
| `request_create`  | Create blood request (validated; lat/lng rounded to 2 dp, geohash/geoOpen; private contact subdoc); limits MAX_REQUESTS_PER_DAY / open reque | user | — | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:44` |
| `request_delete`  | Delete own blood request recursively; notify accepted donors | user (requester) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:162` |
| `request_reopen`  | Reopen within 1 h of closing (undo close marks from meta/close, restore donation dates); pushes donors | user (requester) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/RequestService.kt:105` |
| `response_update`  | Donor cancel/donated or requester removes donor; recount bags, restore/notify standby donors | user (donor: cancel/donated; requester: remove) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/blood/DonorService.kt:41` |

### package `books`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `books_adjust`  | Write-off (loss at cost) or stock count correction | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:595` |
| `books_adjusts`  | Adjustments of last 90 days | user role:approved-pharmacy | pharmacy | — | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:633` |
| `books_item_delete`  | Delete product (history kept); syncs stock list | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:219` |
| `books_item_save`  | Add/edit product; qty change recorded as count adjustment; syncs pharmacyStock | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:150` |
| `books_item_stock`  | Manual in-stock on/off for non-quantity shops | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:230` |
| `books_items`  | Products with stock, price, cost, expiry, low flags | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:139` |
| `books_purchase_add`  | Purchase: qty up, average cost, nearest expiry, optional new MRP; new names create items; day aggregates | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:246` |
| `books_purchase_delete`  | Undo purchase (qty back; undo map); day aggregates | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:331` |
| `books_purchases`  | Purchases of last 90 days (≤200) | user role:approved-pharmacy | pharmacy | app,web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:325` |
| `books_report`  | P&L report for period from daily aggregates + stock valuation | user role:approved-pharmacy | pharmacy | app,web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:663` |
| `books_rx_preview`  | Preview rx dispensing as sale lines (matched items, prices, cost) | user role:approved-pharmacy | pharmacy | app,web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:527` |
| `books_sale_add`  | Counter sale or rx sale (from dispensed lines, once per dispensing); line/bill discounts; profit; day aggregates | user role:approved-pharmacy | pharmacy | app,web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:411` |
| `books_sale_delete`  | Delete sale; qty and day aggregates reversed | user role:approved-pharmacy | pharmacy | web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:565` |
| `books_sales`  | Sales by period (today/7d/30d/month, ≤300) | user role:approved-pharmacy | pharmacy | app,web | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:552` |
| `books_today`  | Today card: sales, net, profit, write-off, low stock, expiring in 60 days, priced flag | user role:approved-pharmacy | pharmacy | app | `backend/src/main/kotlin/com/lifolo/books/BooksService.kt:641` |

### package `care`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `care_invite`  | Invite caregiver by Lifolo email (≤ MAX_CARETAKERS); quota care=10/day; pushes invitee | user | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/care/CareActions.kt:113` |
| `care_list`  | My caregivers, people I care for, family members | user | — | app,web | `backend/src/main/kotlin/com/lifolo/care/CareActions.kt:103` |
| `care_remove`  | Patient removes caregiver or caregiver leaves | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/care/CareActions.kt:204` |
| `care_respond`  | Caregiver accepts/declines invitation; pushes patient | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/care/CareActions.kt:167` |
| `family_get` CARE | Family members (no own account) stored on profile | user caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/care/CareActions.kt:217` |
| `family_set`  | Replace family list (≤ MAX_FAMILY, validated members) | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/care/CareActions.kt:241` |

### package `content`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `admin_badge_resolve`  | Approve/reject badge or correction (verify checks required); delists provider + cancels future appointments on reject; restores listing on r | user admin web-panel | admin, own-row | admin | `backend/src/main/kotlin/com/lifolo/content/BadgeActions.kt:195` |
| `admin_badges`  | Badge applications by status (+ pending corrections and verify panel info when panel:true) | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/content/BadgeActions.kt:146` |
| `admin_hospital_requests`  | Pending hospital requests with possible duplicates | user admin web-panel | admin | — | `backend/src/main/kotlin/com/lifolo/content/Hospitals.kt:278` |
| `admin_hospital_resolve`  | approve / reject / merge into existing; pushes requesters | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/content/Hospitals.kt:298` |
| `admin_hospitals`  | Hospitals of district for emergency form | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/content/Hospitals.kt:189` |
| `answer`  | Verified professional answers; increments answerCount, records answer path for deletion, pushes asker | user role:badge | badge, own-row, quota | app,web,admin | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:203` |
| `ask`  | Post question (≤1000 chars, optional anonymous); quota ask=5/day | user | quota | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:190` |
| `badge_apply`  | Apply for professional badge (doctor/nurse/nutritionist/trainer/expert) with regNo, workplace, doc photos; on approved badge → pendingChange | user | quota | app,web | `backend/src/main/kotlin/com/lifolo/content/BadgeActions.kt:93` |
| `delete_answer`  | Delete answer, decrement answerCount; admin deletion logged | user (author) or admin | admin, own-row | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:300` |
| `delete_question`  | Recursive delete; admin deletion is logged (admin_delete_post) | user (author) or admin | admin, own-row | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:289` |
| `delete_tip`  | Delete tip; admin deletion logged | user (author) or admin | admin, own-row | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:375` |
| `edit_answer`  | Author edits answer; reopens moderation reports | user (author, badge still approved) | badge, own-row | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:271` |
| `edit_question`  | Author edits text/anonymous flag; reopens moderation reports on text change | user (author) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:247` |
| `edit_tip`  | Edit tip; reopens moderation reports | user (author, badge approved) | badge, own-row | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:357` |
| `hospital_request`  | Request a new hospital (dedupe/merge with similar pending, duplicateOf existing); quota hospital=10/day | user | quota | app,web | `backend/src/main/kotlin/com/lifolo/content/Hospitals.kt:213` |
| `hospitals` GUEST | Approved hospitals of district (+upazila) — DGHS directory + approved user additions | guest | — | app,web,admin | `backend/src/main/kotlin/com/lifolo/content/Hospitals.kt:182` |
| `me`  | Own badge (+ docs status), isAdmin, isOwner, phoneCheck enabled, phoneVerified, unread inbox count | user | admin, owner, own-row | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:81` |
| `post_tip`  | Verified professional posts tip (title ≤120, body ≤3000); quota tip=5/day | user role:badge | badge, quota | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:340` |
| `question` GUEST | One question + up to 200 answers; answer author name/badge from live approved badge | guest | — | app,web,admin | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:153` |
| `questions` GUEST | Health Q&A list, 30/page, cursor `before` (createdAt ms); authorName hidden for anonymous or guests | guest | — | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:131` |
| `tips` GUEST | Health tips list, 30/page, cursor `before`; live badge name/type | guest | — | app,web | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:315` |

### package `demo`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `admin_demo_seed`  | Seed demo world step by step (step 0 wipes + creates Auth accounts; steps 1..5 parts); returns {next,label,steps} | owner web-panel | owner | admin | `backend/src/main/kotlin/com/lifolo/demo/DemoSeeder.kt` |
| `admin_demo_status`  | Demo manifest status and role emails | owner web-panel | owner | admin | `backend/src/main/kotlin/com/lifolo/demo/DemoSeeder.kt:313` |
| `admin_demo_wipe`  | Wipe all demo accounts (real account-deletion path) and manifest docs; {more} when time budget exceeded | owner web-panel | owner | admin | `backend/src/main/kotlin/com/lifolo/demo/DemoSeeder.kt:313` |

### package `homecare`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `home_called`  | Record call to home-care provider (+homeStats) | user | same-world | app,web | `backend/src/main/kotlin/com/lifolo/homecare/HomecareActions.kt:197` |
| `home_providers` GUEST | Home-visit doctors/nurses near GPS or district/upazila; service/female filter; canRate (cached list) | guest | own-row | app,web | `backend/src/main/kotlin/com/lifolo/homecare/HomecareActions.kt:150` |
| `home_rate`  | Rate home-care provider within 30 days of call | user | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/homecare/HomecareActions.kt:221` |
| `home_stats`  | Provider own home-care stats (enabled/blocked, calls, rating, comments, reports) | user | — | app,web | `backend/src/main/kotlin/com/lifolo/homecare/HomecareActions.kt:257` |

### package `lab`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `admin_lab_resolve`  | resolveListing for labs | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:177` |
| `admin_labs`  | Labs by status | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:151` |
| `admin_labtests_set`  | Add/edit one lab test template (hide, never delete) | user admin web-panel | admin | admin | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:124` |
| `lab_delete`  | Delete own lab (not suspended) + prices + docs; strips home addresses | user (lab owner) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:254` |
| `lab_form`  | Lab: report form templates for order tests (lab’s own ranges) | user role:approved-lab | lab, own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:647` |
| `lab_home_cancel` CARE | Patient cancels home collection before confirmation (removes address/phone); pushes lab | user (patient of order) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:548` |
| `lab_home_change` CARE | Patient changes home collection details; pushes lab | user (patient of order) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:561` |
| `lab_home_confirm`  | Lab confirms home collection date/time; pushes patient | user role:approved-lab | lab | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:498` |
| `lab_home_decline`  | Lab declines home collection (order stays); pushes patient | user role:approved-lab | lab | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:517` |
| `lab_me`  | Own lab (with docs status) or null | user | — | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:184` |
| `lab_open_code`  | Lab opens rx by code → walk-in order; quota rxPharm | user role:approved-lab | lab, quota | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:445` |
| `lab_order_get` CARE | Read one lab order/report + history | user (patient, lab, or doctor when paid/no fee → else 402) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:630` |
| `lab_order_status`  | Lab: received / cancelled; pushes patient | user role:approved-lab | lab, own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:469` |
| `lab_orders`  | Lab: incoming orders (segments, counts) | user role:approved-lab | lab | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:435` |
| `lab_prices_get`  | Lab owner: own test & price list with catalog | user role:approved-lab | lab, own-row | web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:289` |
| `lab_prices_public` GUEST CARE | Public price list of one approved lab (cached 1 min) | guest caregiver | — | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:322` |
| `lab_prices_set`  | Save whole price list (codes or own names, price, tat, on), discountPct, homeFee; returns kept hidden-code names | user role:approved-lab | lab, own-row | web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:296` |
| `lab_ranges_set`  | Lab: save own normal ranges | user role:approved-lab | lab | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:658` |
| `lab_report`  | Lab submits results (items, note, ≤2 photos) → reported; creates doctor review request; pushes patient | user role:approved-lab | lab, own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:584` |
| `lab_save`  | Register/edit lab (DGHS facilityId or own name, license, hours, homeCollection, docs); re-verify; quota lab=3/day | user | quota | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:200` |
| `labs` GUEST CARE | Approved labs in district (price summary) | guest caregiver | — | app,web,admin | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:269` |
| `labtests_get` CARE | Lab test catalogue (config/labTests merged with defaults) | user (also web-panel, caregiver) | — | app,web,admin | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:122` |
| `review_confirm`  | Doctor/assistant confirms payment ok/not; pushes patient | user role:doctor\|assistant | doctor|assistant, own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:760` |
| `review_done`  | Doctor comments on report (optional newRxId); pushes patient | user role:doctor | doctor, badge, own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:783` |
| `review_pay` CARE | Patient submits TrxID/method; pushes doctor | user (patient of review) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:714` |
| `review_settings`  | Doctor report-review settings (enabled, fee/noFee, pay number, methods) | user role:doctor | doctor, badge, own-row | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:687` |
| `reviews_doctor`  | Doctor/assistant: review requests with TrxIDs | user role:doctor\|assistant | doctor|assistant | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:753` |
| `rx_lab_options` CARE | Patient: labs for rx tests (doctor’s place first, coverage, price, distance), lastHome | user caregiver | own-row, same-world, quota | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:337` |
| `rx_send_lab` CARE | Patient sends tests to lab (optional home collection: address, phone, day, slot, note); price snapshot; quota rxSend; pushes lab | user caregiver | own-row, same-world, quota | app,web | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:388` |

### package `notify`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `device_register`  | Attach FCM token to this account (≤5, removed from other accounts) | user | own-row | app | `backend/src/main/kotlin/com/lifolo/notify/InboxActions.kt:66` |
| `device_unregister`  | Remove this FCM token from account | user | own-row | app | `backend/src/main/kotlin/com/lifolo/notify/InboxActions.kt` |
| `inbox`  | Latest 50 inbox items (read flag from inboxSeenAt) | user | own-row | app,web,admin | `backend/src/main/kotlin/com/lifolo/notify/InboxActions.kt:27` |
| `inbox_read`  | Mark all read (inboxSeenAt = now) | user | — | app,web | `backend/src/main/kotlin/com/lifolo/notify/InboxActions.kt:48` |

### package `pharmacy`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `admin_pharmacies`  | Pharmacies by status | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:565` |
| `admin_pharmacy_resolve`  | resolveListing for pharmacies | user admin web-panel | admin | app,admin | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:595` |
| `drug_search` GUEST | Type-ahead drug search over bundled drugs.json | guest | — | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:115` |
| `med_find`  | "Which shop has it?": push ≤15 nearby open approved pharmacies (find setting on, ≤30/day/shop); quota medFind=5/day | user | own-row, same-world, quota | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:118` |
| `med_find_answer`  | Shop answers have/not; pushes finder on have | user (approved pharmacy) | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:262` |
| `med_find_close`  | Close my find | user (finder) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:221` |
| `med_find_requests`  | Shop: open finds sent to me | user (approved pharmacy) | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:231` |
| `med_finds_mine`  | My last 10 finds with shops answering "have" | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:181` |
| `pharmacies` GUEST CARE | Approved pharmacies in district; modes shop/online, rxId → per-card stock match & estimate; openNow/open24; canRate; same world when mode/rx | guest caregiver | own-row, same-world | app,web,admin | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:333` |
| `pharmacy_called`  | Record call (10-min dedupe) + pharmacyStats | user | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:412` |
| `pharmacy_delete`  | Remove own pharmacy (not suspended); deletes stats, ratings, docs, strips order contacts | user (pharmacy owner) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:245` |
| `pharmacy_find_setting`  | Opt in/out of medicine find requests (findOff) | user (pharmacy owner) | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:290` |
| `pharmacy_me`  | Own pharmacy (with docs status) or null | user | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:107` |
| `pharmacy_pause`  | Temporarily closed until (≤60 days) or reopen | user (pharmacy owner) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:228` |
| `pharmacy_rate`  | Aspect rating (behaviour/price/delivery) after call or purchase; one per user per shop, editable | user | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:432` |
| `pharmacy_save`  | Create/edit pharmacy (license, phone, address, hours, closedDays, discounts, delivery, docs); re-verify; sanctions; quota pharmacy=5/day | user | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:128` |
| `pharmacy_stats`  | Owner: calls, rating, aspects, recent comments, reports | user | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyService.kt:544` |
| `rx_dispense`  | Pharmacy records dispensed quantities (items [{i,qty}], missing) → prescriptions.dispensed[], completes rxOrder, pharmacyMissed; pushes pati | user role:approved-pharmacy | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:88` |
| `rx_missed_clear`  | Pharmacy: clear one (k) or all missed medicines | user role:approved-pharmacy | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:514` |
| `rx_order_update`  | Pharmacy: ready/cancelled (shop) or accepted(price, eta)/out/cancelled (online); pushes patient | user role:approved-pharmacy | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:418` |
| `rx_order_withdraw` CARE | Withdraw order (reason not_available → pharmacyMissed) | user (patient of order) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:366` |
| `rx_orders_pharmacy`  | Pharmacy: incoming orders (segments, counts) | user role:approved-pharmacy | pharmacy | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:398` |
| `rx_pharmacy_home`  | Pharmacy: shop code (generated once), online settings, missed list | user role:approved-pharmacy | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:480` |
| `rx_pharmacy_online_set`  | Pharmacy: online selling enabled, radiusKm, fee, note | user role:approved-pharmacy | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:496` |
| `rx_pharmacy_open`  | Pharmacy opens prescription by code (same world); quota rxPharm=300/day | user role:approved-pharmacy | pharmacy, quota | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:77` |
| `rx_pharmacy_options` CARE | Patient: nearby pharmacies for an rx (shop/online modes, stock match, estimate) | user caregiver | own-row, same-world | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:214` |
| `rx_send_pharmacy` CARE | Patient sends rx to pharmacy (shop or online delivery with address/phone/note); quota rxSend=20/day; pushes shop | user caregiver | own-row, same-world, quota | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:301` |
| `rx_shop_find` CARE | Patient: find shop by 6-char shop code; quota medTry=30/day | user caregiver | own-row, same-world, quota | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:284` |
| `stock_get`  | Pharmacy: in-stock names | user role:approved-pharmacy | pharmacy, own-row | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:528` |
| `stock_set`  | Pharmacy: add/remove stock names (≤3000) | user role:approved-pharmacy | pharmacy | app,web | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:535` |

### package `rx`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `rx_activity` CARE | Patient: pharmacy/lab orders, reviews, dispensed for an rx | user (patient of rx) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:464` |
| `rx_assisting`  | Assistant: doctors I assist | user (verified email listed as assistant) | badge, own-row | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:197` |
| `rx_cancel`  | Cancel prescription | user (writing doctor) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:398` |
| `rx_claim` CARE | Patient claims paper prescription by code + phone (must match profile) → links all of that doctor’s prescriptions for the phone; quota rxCla | user caregiver | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:422` |
| `rx_create`  | Doctor writes prescription (medicines, tests, advice, follow-up, findings…) → code; links app patient or rxPatient; quota rx=300/day; pushes | user role:doctor | doctor, own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:299` |
| `rx_doctor_list`  | Doctor: own recent 150 prescriptions | user role:doctor | doctor | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:408` |
| `rx_get` CARE | One prescription + lab orders/reviews | user (patient or doctor of rx) caregiver | own-row | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:451` |
| `rx_me`  | Doctor: own name, degrees, chamber names | user role:doctor | doctor | web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:163` |
| `rx_mine` CARE | Patient: my prescriptions | user caregiver | — | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:415` |
| `rx_patient_add`  | Doctor/assistant: add paper patient (name, age, gender, phone → phoneKey); dedupe; quota rxPatient=200/day | user role:doctor\|assistant | doctor|assistant, quota | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:268` |
| `rx_patient_history`  | Doctor: earlier prescriptions of a patient (rxPatientId or appointmentId) + lab orders | user role:doctor | doctor, own-row | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:243` |
| `rx_patients`  | Doctor/assistant: entered patients + today’s app appointments | user role:doctor\|assistant | doctor|assistant | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:210` |
| `rx_team`  | Doctor: assistant emails | user role:doctor | doctor | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:171` |
| `rx_team_set`  | Doctor: set assistant emails (≤ limit, not own) | user role:doctor | doctor, own-row | app,web | `backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:177` |

### package `users`

| action | কী করে | auth (doc) | markers | clients | handler |
|---|---|---|---|---|---|
| `delete_account`  | Schedule deletion in 30 days: hide listings (status suspended + hiddenForDeletion), close ambulance requests, suspend provider + cancel appo | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/users/AccountDeletion.kt:38` |
| `phone_send_code`  | SMS code to profile phone (SMS_URL_TEMPLATE; disabled when unset → 400); per-number 3/day + 1/min (phoneLimits); quota phoneCode=5/day | user | own-row, quota | app,web | `backend/src/main/kotlin/com/lifolo/users/PhoneActions.kt:81` |
| `phone_verify_code`  | Verify 6-digit SMS code (5 tries, 10 min) → verifiedPhone | user | own-row | app,web | `backend/src/main/kotlin/com/lifolo/users/PhoneActions.kt:122` |
| `profile_get`  | Own profile subset (profileOut) or null | user (not guest, not caregiver) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/users/ProfileActions.kt:20` |
| `profile_update`  | Merge-update whitelisted fields (SELF_FIELDS), lat+lng together (→ geohash, donorGeo), lastDonation only forward, commitAdd/commitRemove | user (not guest, not caregiver) | own-row | app,web | `backend/src/main/kotlin/com/lifolo/users/ProfileActions.kt:25` |

## চ) অন্য HTTP endpoint

| path | ফাইল |
|---|---|
| `/.well-known/assetlinks.json` | `backend/src/main/kotlin/com/lifolo/blood/ShareController.kt:57` |
| `/api/admin/corrections` | `backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:60` |
| `/api/admin/resolve` | `backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:67` |
| `/api/auth/send-code` | `backend/src/main/kotlin/com/lifolo/auth/AuthController.kt:49` |
| `/api/auth/verify-code` | `backend/src/main/kotlin/com/lifolo/auth/AuthController.kt:67` |
| `/api/close-request` | `backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:52` |
| `/api/community` | `backend/src/main/kotlin/com/lifolo/common/action/CommunityController.kt:35` |
| `/api/cron/daily` | `backend/src/main/kotlin/com/lifolo/jobs/DailyJobs.kt:61` |
| `/api/cron/sentry-test` | `backend/src/main/kotlin/com/lifolo/common/SentrySetup.kt:100` |
| `/api/cron/storage` | `backend/src/main/kotlin/com/lifolo/common/StorageMigrator.kt:199` |
| `/api/cron/storage/migrate` | `backend/src/main/kotlin/com/lifolo/common/StorageMigrator.kt:206` |
| `/api/cron/storage/probe` | `backend/src/main/kotlin/com/lifolo/common/StorageMigrator.kt:220` |
| `/api/edit-request` | `backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:56` |
| `/api/events` | `backend/src/main/kotlin/com/lifolo/notify/SseHub.kt:108` |
| `/api/health` | `backend/src/main/kotlin/com/lifolo/common/HealthController.kt:17` |
| `/api/notify-request` | `backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:44` |
| `/api/respond` | `backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:48` |
| `/api/share` | `backend/src/main/kotlin/com/lifolo/blood/ShareController.kt:31` |
| `/r/{id}` | `backend/src/main/kotlin/com/lifolo/blood/ShareController.kt:48` |
| `/rx/{code}` | `backend/src/main/kotlin/com/lifolo/blood/ShareController.kt:51` |
| `/shop/{code}` | `backend/src/main/kotlin/com/lifolo/blood/ShareController.kt:54` |

## ছ) পুশ নোটিফিকেশন — ধরন, কোথায় তৈরি, চাপলে কোথায় যায়

| type | চাপলে (Android `openFromData`) | কল-সাইট (শিরোনাম) |
|---|---|---|
| `(helper default)` | depends on helper (RxPush → rx/care; AmbReq driver push → services/ambulance) | `backend/src/main/kotlin/com/lifolo/ambulance/AmbRepo.kt:333` <br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:517` ভাড়ার প্রস্তাবের সময় শেষ<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1012` ${res.name} can come for ৳${res.fare}<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1016` ${res.name} আসছে<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1201` $name পৌঁছেছে<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1202` ট্রিপ শেষ — অ্যাম্বুলেন্সকে রেটিং দিন<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:1203` $name বাতিল করেছে<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:153` <br>`backend/src/main/kotlin/com/lifolo/appointments/P2Support.kt:114` <br>`backend/src/main/kotlin/com/lifolo/appointments/Reminders.kt:95` <br>`backend/src/main/kotlin/com/lifolo/blood/BloodLifecycle.kt:63` <br>`backend/src/main/kotlin/com/lifolo/blood/DonorService.kt:119` আপনাকে আবার রিকোয়েস্টে রাখা হয়েছে |
| `amb_req_mine` | AmbulanceRequest(reqId) waiting page | `backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:405`  |
| `answer` | QuestionDetail — questionId | `backend/src/main/kotlin/com/lifolo/content/CommunityActions.kt:228` আপনার প্রশ্নের উত্তর এসেছে |
| `appointment` | role=provider → ChamberEdit (My chamber); else MyAppointments | `backend/src/main/kotlin/com/lifolo/admin/AdminWebActions.kt:405` Lifolo টিম আপনার চেম্বার সরিয়ে দিয়েছে<br>`backend/src/main/kotlin/com/lifolo/appointments/ApptCancel.kt:88` অ্যাপয়েন্টমেন্ট বাতিল হয়েছে<br>`backend/src/main/kotlin/com/lifolo/appointments/P2AccountDeletion.kt:54` <br>`backend/src/main/kotlin/com/lifolo/appointments/QueueActions.kt:275` আপনার আগে ${Lang.bnDigits(ahead.toString())} জন — চেম্বারে চলে আসুন<br>`backend/src/main/kotlin/com/lifolo/appointments/QueueActions.kt:531` ডাক্তার: ঠিক আছে, আপনার সিরিয়াল থাকছে<br>`backend/src/main/kotlin/com/lifolo/appointments/Reminders.kt:61` অ্যাপয়েন্টমেন্টের অনুরোধে সাড়া আসেনি |
| `badge` | Home (falls to else-branch) | `backend/src/main/kotlin/com/lifolo/admin/VerifyActions.kt:121` আপনার ${k.labelBn}-এর কাগজের ছবি দিন<br>`backend/src/main/kotlin/com/lifolo/content/BadgeActions.kt:250` আপনার ব্যাজের তথ্য সংশোধন হয়েছে |
| `care` | with asUid+rxId → CareFor(patient) → that prescription; else Care screen | `backend/src/main/kotlin/com/lifolo/care/CareActions.kt:149` $me আপনাকে কেয়ারগিভার করতে চান<br>`backend/src/main/kotlin/com/lifolo/care/CareActions.kt:183` $name এখন আপনার কেয়ারগিভার |
| `correction` | Home | `backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:147` তারিখ সংশোধন হয়েছে<br>`backend/src/main/kotlin/com/lifolo/blood/BloodHttpController.kt:155` তারিখ সংশোধনের অনুরোধ বাতিল হয়েছে |
| `eligible` | Home | `backend/src/main/kotlin/com/lifolo/blood/BloodLifecycle.kt:54` আপনি আবার রক্ত দিতে পারবেন |
| `emergency` | Home + emergency banner refreshed | `backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:151` জরুরি: ${u.group} রক্ত দরকার<br>`backend/src/main/kotlin/com/lifolo/blood/EmergencyService.kt:195` জরুরি আহ্বান শেষ |
| `hospital` | Home (falls to else-branch) | `backend/src/main/kotlin/com/lifolo/content/Hospitals.kt:308`  |
| `med_find` | MyBusiness(pharmacy) › finds | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:172` কাছে একজন ওষুধ খুঁজছেন |
| `med_find_mine` | Services tab › pharmacy › medicine finder | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt:281` ${shop.name}-এ আছে |
| `request` | RequestDetail (donor view of the request) — requestId | `backend/src/main/kotlin/com/lifolo/blood/DonorService.kt:214` আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন<br>`backend/src/main/kotlin/com/lifolo/blood/DonorService.kt:223` আপাতত যথেষ্ট ডোনার পাওয়া গেছে, ধন্যবাদ!<br>`backend/src/main/kotlin/com/lifolo/blood/RequestFlowService.kt:116` ${request.patientGroup} রক্ত দরকার${if (request.urgent) <br>`backend/src/main/kotlin/com/lifolo/blood/RequestFlowService.kt:323` রক্ত দেওয়ার জন্য ধন্যবাদ!<br>`backend/src/main/kotlin/com/lifolo/blood/RequestFlowService.kt:466` আবার ডোনার লাগতে পারে<br>`backend/src/main/kotlin/com/lifolo/blood/RequestFlowService.kt:481` ${e.before.patientGroup} রক্তের রিকোয়েস্টে পরিবর্তন |
| `request_removed` | Home (no requestId) | `backend/src/main/kotlin/com/lifolo/blood/RequestEndService.kt:54`  |
| `response` | MyRequestDetail (requester view) — requestId | `backend/src/main/kotlin/com/lifolo/blood/DonorService.kt:144` একজন নিশ্চিত ডোনার বাতিল করেছেন<br>`backend/src/main/kotlin/com/lifolo/blood/RequestFlowService.kt:224` একজন ডোনার রাজি হয়েছেন |
| `rx` | that prescription (rxId) or MyPrescriptions | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:612` আপনার পরীক্ষার রিপোর্ট এসেছে<br>`backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:164` ${shop.name}-এ কিছু ওষুধ নেই<br>`backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:169` প্রেসক্রিপশনের ওষুধ দেওয়া হয়েছে<br>`backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:466` <br>`backend/src/main/kotlin/com/lifolo/rx/RxActions.kt:393` $doctorName নতুন প্রেসক্রিপশন দিয়েছেন<br>`backend/src/main/kotlin/com/lifolo/rx/RxDaily.kt:27` কাল $name-এর কাছে আবার দেখানোর দিন<br>`backend/src/main/kotlin/com/lifolo/rx/RxDaily.kt:55` ২ দিন পর ওষুধ শেষ হবে — কিনে নিন |
| `rx_lab` | MyBusiness(lab) › orders | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:421` নতুন পরীক্ষা — বাসা থেকে নমুনা<br>`backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:553` বাসায় যেতে হবে না — রোগী প্যাথলজিতে এসে নমুনা দেবেন<br>`backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:572` রোগী বাসা থেকে নমুনার সময় বদলেছেন |
| `rx_pharmacy` | MyBusiness(pharmacy) › orders | `backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:355` নতুন অনলাইন অর্ডার<br>`backend/src/main/kotlin/com/lifolo/pharmacy/RxPharmacyService.kt:388` ক্রেতা প্রেসক্রিপশন ফিরিয়ে নিয়েছেন |
| `rx_review` | RxReviews (doctor/assistant) | `backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:623` একটা রিপোর্ট দেখার অপেক্ষায়<br>`backend/src/main/kotlin/com/lifolo/lab/LabActions.kt:729` রিপোর্ট দেখার টাকা মিলিয়ে নিন |
| `services` | MyBusiness(kind) when kind ∈ ambulance/pharmacy/bloodbank/lab, else My work tab or Home | `backend/src/main/kotlin/com/lifolo/admin/kernel/ListingReview.kt:168` রিপোর্টের পর আপনার ${k.labelBn} বন্ধ করা হয়েছে। প্রোফাইলের \<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:387` কাছেই<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:390` অ্যাম্বুলেন্স দরকার — $where<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbReqService.kt:410` রোগী অন্য গাড়ি নিয়েছেন<br>`backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceService.kt:143` ${a.name} — এখনো খালি আছেন?<br>`backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:354` Lifolo টিম আপনার ব্লাড ব্যাংকের তথ্য ঠিক করেছে<br>`backend/src/main/kotlin/com/lifolo/blood/BloodBankService.kt:386` আপনার ব্লাড ব্যাংক তালিকা থেকে সরানো হয়েছে |
| `support` | Help screen | `backend/src/main/kotlin/com/lifolo/admin/SupportActions.kt:197` Lifolo টিমের উত্তর এসেছে |

অ্যাপের নোটিফিকেশন চ্যানেল: `CHANNEL` (ওষুধ খাওয়ার অ্যালার্ম), `CHANNEL_ID` (রক্তের রিকোয়েস্ট) + `blood_requests` (FCM ডিফল্ট)। ওষুধের অ্যালার্ম ফোনেই (`MedAlarmReceiver`, বুটের পর `MedAlarmBootReceiver`)। ওয়েবে পুশ নেই (শুধু ঘণ্টার তালিকা + SSE `/api/events`)।

## জ) ইমেইল

| ধরন | কখন | কোড |
|---|---|---|
| লগইন কোড (৬ সংখ্যা, ১০ মিনিট) | `/api/auth/send-code` (অ্যাপ, ওয়েব, অ্যাডমিন প্যানেল) | `notify/MailSender.kt` (Amazon SES), `auth/AuthController.kt` |

কেয়ারগিভারের আমন্ত্রণ ইমেইলে যায় না — শুধু পুশ/ঘণ্টা (যাঁকে আমন্ত্রণ, তাঁর Lifolo অ্যাকাউন্ট থাকতে হয়)। অপারেশনের ইমেইল: Uptime check-এর GitHub ইমেইল, Sentry (চালু হলে)।

## ঝ) ক্রন ও ব্যাকগ্রাউন্ড কাজ

| ধরন | নাম | কখন | ফাইল |
|---|---|---|---|
| DailyTask (03:00 UTC) | `AccountsDeletedTask` | order 10 | `backend/src/main/kotlin/com/lifolo/users/AccountDeletion.kt` |
| DailyTask (03:00 UTC) | `AmbulanceRemindersTask` | order 12 | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceModule.kt` |
| DailyTask (03:00 UTC) | `AmbulanceRequestsTask` | order 13 | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceModule.kt` |
| DailyTask (03:00 UTC) | `AppointmentReminders` | order 3 | `backend/src/main/kotlin/com/lifolo/appointments/Reminders.kt` |
| DailyTask (03:00 UTC) | `CloseEmergenciesTask` | order 4 | `backend/src/main/kotlin/com/lifolo/blood/BloodLifecycle.kt` |
| DailyTask (03:00 UTC) | `EligibleAgainTask` | order 2 | `backend/src/main/kotlin/com/lifolo/blood/BloodLifecycle.kt` |
| DailyTask (03:00 UTC) | `ExpireRequestsTask` | order 1 | `backend/src/main/kotlin/com/lifolo/blood/BloodLifecycle.kt` |
| DailyTask (03:00 UTC) | `FollowUpReminders` | order 8 | `backend/src/main/kotlin/com/lifolo/rx/RxDaily.kt` |
| DailyTask (03:00 UTC) | `InboxCleanupTask` | order 11 | `backend/src/main/kotlin/com/lifolo/notify/InboxActions.kt` |
| DailyTask (03:00 UTC) | `MedFindCleanupTask` | order 5 | `backend/src/main/kotlin/com/lifolo/pharmacy/MedFindService.kt` |
| DailyTask (03:00 UTC) | `PhoneLimitsTask` | order 6 | `backend/src/main/kotlin/com/lifolo/users/PhoneActions.kt` |
| DailyTask (03:00 UTC) | `RefillReminders` | order 9 | `backend/src/main/kotlin/com/lifolo/rx/RxDaily.kt` |
| DailyTask (03:00 UTC) | `RxOrderPrivacyTask` | order 14 | `backend/src/main/kotlin/com/lifolo/pharmacy/PharmacyModule.kt` |
| JobRunr @Recurring | `ambulance-timers` | * * * * * UTC | `backend/src/main/kotlin/com/lifolo/ambulance/AmbulanceModule.kt` |
| JobRunr @Recurring | `daily-maintenance` | 0 3 * * * UTC | `backend/src/main/kotlin/com/lifolo/jobs/DailyJobs.kt` |
| JobRunr @Recurring | `storage-trash` | * * * * * UTC | `backend/src/main/kotlin/com/lifolo/common/StorageMigrator.kt` |
| VPS systemd | `lifolo-backup.timer` (pg_dump, ১৪ দিন রাখা) | 21:30 UTC রোজ | `deploy/setup-base.sh` |
| VPS systemd | `certbot.timer` (Let's Encrypt নবায়ন) | দিনে ২ বার | `deploy/setup-base.sh` |
| GitHub Actions | Uptime check (`/api/health`) | প্রতি ৩ ঘণ্টা (cron `23 */3 * * *`) | `.github/workflows/uptime.yml` |
| GitHub Actions | Backup check (restore test, R2 কপি) | সোমবার 23:17 UTC | `.github/workflows/backup-check.yml` |
| GitHub Actions | Backend e2e (web journeys vs Spring) | শনিবার 21:47 UTC (৭ দিনে বদল থাকলে) | `.github/workflows/backend-e2e.yml` |
| GitHub Actions | Android e2e (Maestro, প্রোডাকশন ডেমোতে) | রবিবার 22:17 UTC | `.github/workflows/e2e-android-cutover.yml` |
| অলস (lazy) | অ্যাম্বুলেন্স অনুরোধের ঢেউ/মেয়াদ (৬০ সে, ৩ মি, ২ মি অফার, ১২ ঘ, ২৪ ঘ purge) | প্রতি পড়া/লেখায় + `ambulance-timers` প্রতি মিনিটে | `ambulance/AmbReqService.kt` |
| অলস | "এখনো খালি আছেন?" (১১ ঘণ্টা) | তালিকা খুললে + দৈনিক | `ambulance/AmbulanceService.kt` |

## ঞ) শেয়ার লিংক, QR, অ্যাপ-লিংক

| কী | কোথায় তৈরি | কে খোলে |
|---|---|---|
| রক্তের রিকোয়েস্ট লিংক `https://lifolo.com/r/{id}` | অ্যাপ `shareRequest` (`SHARE_BASE`), ওয়েব | ব্রাউজারে সার্ভারের পাতা (নাম/ফোন/নোট ছাড়া); অ্যাপ থাকলে App Link (`autoVerify`) |
| পুরোনো লিংক `https://blood-server-ashy.vercel.app/r/{id}` | ২.৩৪.০-এর আগের অ্যাপ | অ্যাপে খোলে (আলাদা intent-filter) — Vercel বন্ধ হলে ব্রাউজারে খুলবে না |
| `getblood://r/{id}` | শেয়ার পাতার "অ্যাপে খুলুন" | অ্যাপ |
| প্রেসক্রিপশন যাচাই `/rx/{code}` + QR `/api/share?rxqr=CODE` | প্রেসক্রিপশন, PDF (`RxPdf.kt`) | যে কেউ — রোগীর নাম, বয়স, ওষুধ, পরীক্ষা দেখায় (by design) |
| দোকানের পোস্টার `/shop/{code}` + QR `?shopqr=` | ফার্মেসি "ছাপার পোস্টার খুলুন" | রোগী স্ক্যান করে দোকানে প্রেসক্রিপশন পাঠান (Google code scanner, ক্যামেরা permission লাগে না) |
| `/.well-known/assetlinks.json` | Spring | Android App Links যাচাই — **release key-এর SHA-256 লাগবে** |
| Google Maps → "শেয়ার" → Lifolo (`ShareActivity`, `text/plain`) | — | অ্যাম্বুলেন্স ডাকার জায়গা |
| প্রেসক্রিপশন PDF শেয়ার (`FileProvider`) | `RxPdf.share` | অন্য অ্যাপ |

## ট) Permission prompt

Manifest: `INTERNET`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`

| ফাইল:লাইন | কোড |
|---|---|
| `Ambulance.kt:913` | `val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->` |
| `Ambulance.kt:929` | `else launcher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))` |
| `LocationInput.kt:168` | `val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->` |
| `LocationInput.kt:261` | `launcher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))` |
| `LocationPicker.kt:67` | `val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->` |
| `LocationPicker.kt:83` | `else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)` |
| `LocationPicker.kt:141` | `val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->` |
| `LocationPicker.kt:146` | `onClick = { if (LocationHelper.hasPermission(context)) locate() else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },` |
| `MainScreen.kt:95` | `val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }` |
| `MainScreen.kt:101` | `notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)` |
| `Profile.kt:84` | `val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->` |
| `Verify.kt:106` | `val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->` |

নোট: ক্যামেরা permission নেই (QR = Google code scanner); ছবি = Photo Picker (permission লাগে না); exact alarm permission manifest-এ নেই → Android 12+ এ ওষুধের অ্যালার্ম inexact (`MedAlarms.kt:161`)।

## ঠ) সেটিংস

| কোথায় | সেটিং |
|---|---|
| অ্যাপ সেটিংস (`Settings.kt`) | ভাষা (বাংলা/English); থিম (ফোনের মতো/লাইট/ডার্ক); নোটিফিকেশন: "আশেপাশে কারো রক্ত লাগলে" চালু/বন্ধ, "দিনে সর্বোচ্চ", "রোগীর যে গ্রুপগুলোর জন্য"; "রক্ত দিতে কতদূর যেতে পারবেন?" (কিমি); "যাতায়াত খরচ"; "যে গ্রুপের রিকোয়েস্ট দেখাবে"; এলাকা; "আপনার ব্যবসা যোগ করুন"; প্রাইভেসি পলিসি; ব্যবহারের শর্ত; "সাপোর্টে যোগাযোগ"; ভার্সন |
| প্রোফাইল (`Profile.kt`) | প্রোফাইল এডিট (নাম, মোবাইল, ছবি, রক্তদাতার তথ্য), রোল বদলান (ডেমো), লগআউট, অ্যাকাউন্ট মুছুন, ব্যবসা যোগ করুন, কেয়ারগিভার ও পরিবার, সাহায্য ও আপিল, ফোন যাচাই (SMS চালু থাকলে) |
| ব্যবসার সেটিং | ফার্মেসি: অনলাইনে বিক্রি (দূরত্ব, ফি, নোট), ওষুধ খোঁজার অনুরোধ নেওয়া, কয়েক দিন বন্ধ; অ্যাম্বুলেন্স: "এখন খালি আছি", ভাড়ার চার্ট, অপেক্ষার জায়গা; ডাক্তার: চেম্বার, "তালিকায় আমাকে দেখাও", "অ্যাপে অ্যাপয়েন্টমেন্ট নেব", বাসায় সেবা, সহকারী, রিপোর্ট দেখার ফি; প্যাথলজি: দাম, বাসা থেকে নমুনা, স্বাভাবিক মাত্রা |
| ওয়েব (`/settings`) | ভাষা, থিম, এলাকা (ব্রাউজারে রাখা), নোটিফিকেশনের পছন্দ (প্রোফাইলে) |
| সার্ভার (env, `/etc/lifolo/app.env`) | `ADMIN_EMAILS`, `CRON_SECRET`, `WEB_SESSION_SECRET`, SES, `PUSH_MODE`, R2, `SENTRY_DSN`, `SMS_URL_TEMPLATE` (না থাকলে ফোন যাচাই বন্ধ), `DEMO_KEEP_FIREBASE_USERS` |
