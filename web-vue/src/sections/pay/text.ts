// LIFOLO PAY-এর লেখা — সব নতুন (React ওয়েবে আগের রূপ নেই), তাই প্রতিটা বাংলা লাইনে bn-ok (scripts/check-bangla.py)।
import { num, tr, trPlain } from '@/lib/i18n';
import type { AcctType, BizKind, PayMethodKind, PayPurpose, PayStatus, RejectReason, StepKind } from './model';

export const T = {
  // শিট
  sheetTitle: () => tr('Pay', 'পেমেন্ট'), // bn-ok
  payTo: (biz: string) => tr(`To ${biz}`, `${biz}-কে`), // bn-ok
  amount: () => tr('Amount', 'টাকা'), // bn-ok
  pickMethod: () => tr('Pay with', 'কীভাবে পাঠাবেন'), // bn-ok
  numberOf: (m: string) => tr(`${m} number`, `${m} নম্বর`), // bn-ok
  copy: () => tr('Copy', 'কপি'), // bn-ok
  copied: () => tr('Copied', 'কপি হয়েছে'), // bn-ok
  numberCopied: () => tr('Number copied', 'নম্বর কপি হয়েছে'), // bn-ok
  howTo: () => tr('How to pay', 'কীভাবে পাঠাবেন'), // bn-ok
  stepOpen: (app: string, code: string, op: string) => tr(`Open ${app} (or dial ${code}) and choose "${op}"`, `${app} অ্যাপ (বা ${code}) খুলে "${op}" করুন`), // bn-ok
  stepSend: (amount: string) => tr(`Send ${amount} to the number above`, `উপরের নম্বরে ${amount} পাঠান`), // bn-ok
  stepMerchantRef: () => tr('For "Payment", write the reference shown above if asked', '"Payment"-এ রেফারেন্স চাইলে উপরের রেফারেন্স লিখুন'), // bn-ok
  stepTell: () => tr('Write the TrxID from the SMS and the last 4 digits of the number you sent from, then tap "I\'ve sent"', 'মেসেজে আসা TrxID আর যে নম্বর থেকে পাঠিয়েছেন তার শেষ ৪ অঙ্ক লিখে "পাঠিয়েছি" চাপুন'), // bn-ok
  stepWait: (biz: string) => tr(`When ${biz} checks it, it shows "Paid" and you get the receipt`, `${biz} মিলিয়ে নিলে "পরিশোধিত" দেখাবে, রসিদও পাবেন`), // bn-ok
  personal: () => tr('Personal', 'পার্সোনাল'), // bn-ok
  merchant: () => tr('Merchant', 'মার্চেন্ট'), // bn-ok
  reference: () => tr('Reference', 'রেফারেন্স'), // bn-ok
  trx: () => tr('TrxID', 'TrxID'),
  trxHint: (m: PayMethodKind) => (m === 'bkash' ? tr('10 letters and digits, from the SMS', 'মেসেজের ১০ অক্ষরের TrxID') : tr('6 to 20 letters and digits, from the SMS', 'মেসেজের ৬–২০ অক্ষরের TrxID')), // bn-ok
  trxPh: (m: PayMethodKind) => (m === 'bkash' ? tr('e.g. 9GH7K2LM1P', 'যেমন: 9GH7K2LM1P') : tr('e.g. 7A3K9Q2X', 'যেমন: 7A3K9Q2X')), // bn-ok
  last4: () => tr('Last 4 digits of your number', 'যে নম্বর থেকে পাঠিয়েছেন, তার শেষ ৪ অঙ্ক'), // bn-ok
  last4Ph: () => tr('e.g. 5678', 'যেমন: ৫৬৭৮'), // bn-ok
  sent: () => tr("I've sent", 'পাঠিয়েছি'), // bn-ok
  sentToast: (biz: string) => trPlain(`Done. ${biz} will check it.`, `জানানো হয়েছে। ${biz} মিলিয়ে দেখবেন।`), // bn-ok
  errTrx: (m: PayMethodKind) => (m === 'bkash' ? trPlain('The bKash TrxID has exactly 10 letters and digits — copy it from the SMS', 'bKash-এর TrxID ঠিক ১০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা) — মেসেজ থেকে হুবহু লিখুন') : trPlain('The TrxID has 6 to 20 letters and digits — copy it from the SMS', 'TrxID ৬ থেকে ২০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা) — মেসেজ থেকে হুবহু লিখুন')), // bn-ok
  errLast4: () => trPlain('Write the last 4 digits of the number you sent from', 'যে নম্বর থেকে পাঠিয়েছেন তার শেষ ৪ অঙ্ক লিখুন'), // bn-ok
  errPickMethod: () => trPlain('Pick how you sent it', 'কীভাবে পাঠিয়েছেন বেছে নিন'), // bn-ok
  trxUsed: () => trPlain("This TrxID was already claimed at this business. If you sent it yourself, report it — don't send the money again.", "এই TrxID দিয়ে এই দোকানে আগে দাবি হয়েছে। আপনি নিজে পাঠিয়ে থাকলে 'রিপোর্ট' করুন — আবার টাকা পাঠাবেন না।"), // bn-ok
  trxRejected: () => trPlain('The business rejected this TrxID — report it.', 'এই TrxID দোকান বাতিল করেছে — রিপোর্ট করুন।'), // bn-ok
  claimRate: () => trPlain('Told too many times. Try again in an hour.', 'অনেকবার জানানো হয়েছে। এক ঘণ্টা পর আবার চেষ্টা করুন।'), // bn-ok
  claimDayRate: () => trPlain('Too many payments told today. Try again tomorrow.', 'আজ অনেকগুলো পেমেন্ট জানানো হয়েছে। কাল আবার চেষ্টা করুন।'), // bn-ok
  oneOpenClaim: () => trPlain('Wait until your other payment is checked, then tell this one.', 'আপনার আরেকটা পেমেন্ট মেলানো বাকি — ওটা মিললে তারপর এটা জানান।'), // bn-ok
  expiredErr: () => trPlain('This payment request has expired.', 'এই পেমেন্টের সময় পেরিয়ে গেছে।'), // bn-ok
  noBlood: () => trPlain('No payment of any kind for blood.', 'রক্তের জন্য কোনো টাকা লেনদেন নেই।'), // bn-ok
  pgwOff: () => trPlain('Online payment is not available right now.', 'অনলাইন পেমেন্ট এখন চালু নেই।'), // bn-ok
  // নিরাপত্তা (PAYMENTS-SECURITY-REVIEW P2, P8, P11)
  onlyHere: () => tr('Send money only to the number shown here. If someone gives another number in chat or on the phone, do not send — report it.', 'শুধু এখানে দেখানো নম্বরে টাকা পাঠান। কেউ চ্যাটে বা ফোনে অন্য নম্বর দিলে পাঠাবেন না, রিপোর্ট করুন।'), // bn-ok
  noPin: () => tr('Lifolo never asks for your PIN, OTP or money.', 'Lifolo কখনো PIN, OTP বা টাকা চায় না।'), // bn-ok
  checkNumber: () => tr('Check the number again before sending', 'পাঠানোর আগে নম্বরটা আবার মিলিয়ে নিন'), // bn-ok
  demoBanner: () => tr('Demo — do not send real money', 'ডেমো — আসল টাকা পাঠাবেন না'), // bn-ok
  noMethods: () => tr("This business doesn't take mobile payments yet — pay in cash", 'এই প্রতিষ্ঠান এখনো অনলাইন পেমেন্ট নিচ্ছে না — নগদে দিন'), // bn-ok
  refundPolicyTitle: () => tr('Refunds', 'টাকা ফেরতের নিয়ম'), // bn-ok
  refundAdvance: (hours: number) =>
    hours > 0
      ? tr(`Full refund if the doctor cancels; if you cancel at least ${hours} hours before, you get it back.`, `ডাক্তার বাতিল করলে পুরো টাকা ফেরত; আপনি ${num(hours)} ঘণ্টা আগে পর্যন্ত বাতিল করলে ফেরত।`) // bn-ok
      : tr('Full refund if the doctor cancels. Not refundable if you cancel.', 'ডাক্তার বাতিল করলে পুরো টাকা ফেরত। আপনি বাতিল করলে ফেরত হয় না।'), // bn-ok
  doctorCancelFull: () => tr('Full refund if the doctor cancels.', 'ডাক্তার বাতিল করলে পুরো টাকা ফেরত।'), // bn-ok
  refundUntil: (when: string) => tr(`Refundable if you cancel before ${when}`, `${when}-এর আগে বাতিল করলে ফেরত`), // bn-ok
  refundGeneral: (biz: string) => tr(`If ${biz} cancels or can't provide it, they must send the money back to the number you paid from.`, `${biz} বাতিল করলে বা দিতে না পারলে, যে নম্বর থেকে দিয়েছেন সেখানেই টাকা ফেরত দিতে হবে।`), // bn-ok
  refundFare: () => tr("Pay only the fare you agreed. If you're charged more than the receipt, report it.", 'যে ভাড়া ঠিক হয়েছে শুধু সেটাই দিন। রসিদের চেয়ে বেশি চাইলে রিপোর্ট করুন।'), // bn-ok
  rejectedTitle: () => tr('The business says the money did not come', 'দোকান বলছে টাকা আসেনি'), // bn-ok
  rejectedWhat: () => tr('What you told', 'আপনি যা জানিয়েছিলেন'), // bn-ok
  rejectedWhen: (when: string) => tr(`Rejected ${when}`, `বাতিল করেছে ${when}`), // bn-ok
  rejectedIfWrong: () => tr('Typed the TrxID wrong? Tell again below. Sure you paid? Dispute it — the TrxID stays saved.', 'TrxID ভুল লিখেছিলেন? নিচে আবার জানান। নিশ্চিত টাকা পাঠিয়েছেন? আপত্তি জানান — TrxID সংরক্ষিত থাকে।'), // bn-ok
  dispute: () => tr('Dispute', 'আপত্তি জানান'), // bn-ok
  sentBy: (name: string) => tr(`Sent by caregiver ${name}`, `কেয়ারগিভার ${name} পাঠিয়েছেন`), // bn-ok
  refundDueHead: (biz: string) => tr(`Refund owed — ${biz}`, `ফেরত পাওনা — ${biz}`), // bn-ok
  refundDueText: () => tr('The business has to send the money back. You will be told when they do.', 'ব্যবসাকে টাকা ফেরত দিতে হবে। দিলে আপনাকে জানানো হবে।'), // bn-ok
  refundClaimedHead: (amount: string) => tr(`The business says it sent ${amount} back`, `ব্যবসা বলছে ${amount} ফেরত পাঠিয়েছে`), // bn-ok
  refundCheck: () => tr('Check your mobile wallet, then tell us.', 'নিজের মোবাইল ওয়ালেটে মিলিয়ে তারপর জানান।'), // bn-ok
  refundNotGotBtn: () => tr("Didn't get it", 'পাইনি'), // bn-ok
  advanceDue: () => tr('Advance due', 'অগ্রিম বাকি'), // bn-ok
  sentByCare: () => tr('Sent by a caregiver from their own wallet', 'কেয়ারগিভার নিজের ওয়ালেট থেকে পাঠিয়েছেন'), // bn-ok
  paused: () => trPlain('Payment requests are paused until the owed refunds are sent.', 'পাওনা ফেরত না দেওয়া পর্যন্ত নতুন পেমেন্ট চাওয়া বন্ধ।'), // bn-ok
  alreadyPaid: () => trPlain('This is already paid.', 'এটা আগেই পরিশোধ হয়েছে।'), // bn-ok
  noAmount: () => trPlain('Nothing to pay here yet — make the bill or receipt first.', 'এখানে এখনো দেওয়ার কিছু নেই — আগে বিল বা রসিদ করুন।'), // bn-ok
  notFinished: () => trPlain('The trip is not finished with a receipt yet.', 'ট্রিপ এখনো রসিদসহ শেষ হয়নি।'), // bn-ok
  amountFromSource: () => tr('The amount comes from the order or bill.', 'টাকার পরিমাণ অর্ডার বা বিল থেকে নেওয়া হবে।'), // bn-ok
  refundOwed: () => tr('Refund owed — send it back', 'ফেরত দিতে হবে'), // bn-ok
  directNote: (biz: string) => tr(`The money goes straight to ${biz}. Lifolo doesn't hold or take any of it. Never share your PIN or OTP.`, `টাকা সরাসরি ${biz}-এর কাছে যায়। Lifolo টাকা রাখে না, কিছু কাটে না। PIN বা OTP কাউকে বলবেন না।`), // bn-ok
  cashOk: () => tr('You can also pay in cash.', 'চাইলে নগদেও দিতে পারেন।'), // bn-ok
  checking: (biz: string) => tr(`${biz} is checking the payment. Until they confirm, it is not paid yet.`, `${biz} টাকা মিলিয়ে দেখছেন। নিশ্চিত না করা পর্যন্ত এটা পরিশোধিত নয়।`), // bn-ok
  youSent: () => tr('You told', 'আপনি জানিয়েছেন'), // bn-ok
  rejectedHead: (biz: string) => tr(`${biz} didn't find this payment. Check the number, amount and TrxID, then tell again.`, `${biz} এই টাকা পাননি। নম্বর, টাকা আর TrxID মিলিয়ে আবার জানান।`), // bn-ok
  reasonIs: (r: string) => tr(`Reason: ${r}`, `কারণ: ${r}`), // bn-ok
  paidHead: () => tr('Paid', 'পরিশোধিত'), // bn-ok
  expiredHead: () => tr('The time to pay is over. Ask them again if you still need to pay.', 'পেমেন্টের সময় পেরিয়ে গেছে। এখনো দিতে হলে তাঁদের আবার বলুন।'), // bn-ok
  cancelledHead: () => tr('This payment was cancelled. Nothing to pay.', 'এই পেমেন্ট বাতিল হয়েছে। কিছু দিতে হবে না।'), // bn-ok
  expiresAt: (when: string) => tr(`Pay by ${when}`, `${when}-এর মধ্যে পাঠান`), // bn-ok
  refundHead: (amount: string) => tr(`${amount} was sent back to you`, `আপনাকে ${amount} ফেরত পাঠানো হয়েছে`), // bn-ok
  refundTrx: (trx: string) => tr(`Refund TrxID: ${trx}`, `ফেরতের TrxID: ${trx}`), // bn-ok
  refundAck: () => tr('I got it', 'পেয়েছি'), // bn-ok
  refundAcked: () => tr('You said you got the refund', 'আপনি ফেরত পেয়েছেন বলে জানিয়েছেন'), // bn-ok
  refundNotGot: () => tr("Didn't get it? Report it.", 'পাননি? সমস্যা জানান।'), // bn-ok
  report: () => tr('Report a problem', 'সমস্যা জানান'), // bn-ok
  reportTitle: () => tr('Report a payment problem', 'পেমেন্টের সমস্যা জানান'), // bn-ok
  reportWhat: () => tr('What happened?', 'কী হয়েছে?'), // bn-ok
  reportNote: () => tr('Details (optional)', 'বিস্তারিত (ঐচ্ছিক)'), // bn-ok
  reportSend: () => tr('Send report', 'রিপোর্ট পাঠান'), // bn-ok
  reportDone: () => trPlain('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।'), // bn-ok
  reportHint: () => tr("Lifolo doesn't hold the money, so it can't pay you back — but an admin will look into it and can act against the business.", 'Lifolo টাকা রাখে না, তাই নিজে ফেরত দিতে পারে না — তবে অ্যাডমিন দেখবেন, দরকারে ব্যবসার বিরুদ্ধে ব্যবস্থা নেবেন।'), // bn-ok
  timeline: () => tr('Status', 'অবস্থা'), // bn-ok
  receipt: () => tr('Receipt', 'রসিদ দেখুন'), // bn-ok
  loadErr: () => trPlain("Couldn't load the payment", 'পেমেন্ট আনা যায়নি'), // bn-ok
  close: () => tr('Close', 'বন্ধ'), // bn-ok
  // প্রবেশের বোতাম
  payNow: () => tr('Pay', 'পেমেন্ট করুন'), // bn-ok
  payAdvance: () => tr('Pay the advance', 'অগ্রিম দিন'), // bn-ok
  payDelivery: () => tr('Pay now (bKash/Nagad)', 'এখনই দিন (বিকাশ/নগদ)'), // bn-ok
  payLab: () => tr('Pay the lab', 'প্যাথলজির টাকা দিন'), // bn-ok
  payFare: () => tr('Pay the fare by mobile banking', 'মোবাইল ব্যাংকিংয়ে ভাড়া দিন'), // bn-ok
  fareOptional: () => tr('Optional — paying the driver in cash is fine too.', 'ঐচ্ছিক — চালককে নগদে দিলেও চলবে।'), // bn-ok
  payDue: () => tr('Pay the due', 'বাকি পরিশোধ করুন'), // bn-ok
  seePayment: () => tr('Payment', 'পেমেন্ট দেখুন'), // bn-ok
  // চেম্বারের অগ্রিম
  advanceLine: (amount: string, hours: number) =>
    hours > 0
      ? tr(`Booking needs ${amount} advance. Refundable if you cancel at least ${hours} hours before.`, `এই চেম্বারে বুকিংয়ে ${amount} অগ্রিম লাগে। সময়ের অন্তত ${num(hours)} ঘণ্টা আগে বাতিল করলে ফেরত পাবেন।`) // bn-ok
      : tr(`Booking needs ${amount} advance. It is not refundable.`, `এই চেম্বারে বুকিংয়ে ${amount} অগ্রিম লাগে। এটা ফেরত হয় না।`), // bn-ok
  advanceAfterBook: () => tr('Request sent. Pay the advance to keep your serial.', 'অনুরোধ গেছে। সিরিয়াল রাখতে অগ্রিম দিন।'), // bn-ok
  advanceLater: () => tr('Pay later', 'পরে দেব'), // bn-ok
  // রোগীর তালিকা
  payments: () => tr('Payments', 'পেমেন্ট'), // bn-ok
  myPayments: () => tr('My payments', 'আমার পেমেন্ট'), // bn-ok
  noPayments: () => tr('No payments yet', 'এখনো কোনো পেমেন্ট নেই'), // bn-ok
  noPaymentsText: () => tr('Payments you send by bKash, Nagad, Rocket or Upay through Lifolo appear here.', 'Lifolo থেকে বিকাশ, নগদ, রকেট বা উপায়ে পাঠানো পেমেন্ট এখানে থাকবে।'), // bn-ok
  pickPayment: () => tr('Pick a payment', 'একটা পেমেন্ট বেছে নিন'), // bn-ok
  loadMore: () => tr('Load more', 'আরও দেখুন'), // bn-ok
  // ব্যবসা
  bizTitle: () => tr('Payments', 'পেমেন্ট'), // bn-ok
  inbox: () => tr('To check', 'মেলানো বাকি'), // bn-ok
  all: () => tr('All payments', 'সব পেমেন্ট'), // bn-ok
  settings: () => tr('Settings', 'সেটিং'), // bn-ok
  whichBiz: () => tr('Which business', 'কোন ব্যবসা'), // bn-ok
  noBiz: () => tr('No business that can take payments', 'পেমেন্ট নেওয়ার মতো কোনো ব্যবসা নেই'), // bn-ok
  today: () => tr('Today', 'আজ'), // bn-ok
  collectedToday: () => tr('Collected today', 'আজ পেয়েছেন'), // bn-ok
  pendingClaims: () => tr('Waiting for you to check', 'মেলানোর অপেক্ষায়'), // bn-ok
  openRequests: () => tr('Not paid yet', 'এখনো পাঠাননি'), // bn-ok
  confirmedCount: (k: number) => tr(`${k} payments`, `${num(k)}টা পেমেন্ট`), // bn-ok
  inboxEmpty: () => tr('Nothing to check', 'মেলানোর কিছু নেই'), // bn-ok
  inboxEmptyText: () => tr('When a patient says they have sent money, it shows here. Check your own bKash/Nagad first, then confirm.', 'রোগী টাকা পাঠিয়েছি জানালে এখানে আসবে। আগে নিজের বিকাশ/নগদে মিলিয়ে তারপর নিশ্চিত করুন।'), // bn-ok
  received: () => tr('Received', 'পেয়েছি'), // bn-ok
  notReceived: () => tr('Not received', 'পাইনি'), // bn-ok
  confirmTitle: () => tr('Money received?', 'টাকা পেয়েছেন?'), // bn-ok
  checkInApp: () => tr('Patient says sent — check in your own app', 'রোগী বলছেন পাঠিয়েছেন — আপনার অ্যাপে মিলিয়ে নিন'), // bn-ok
  checkTrx: () => tr('I found this TrxID in my wallet', 'এই TrxID আমার ওয়ালেটে পেয়েছি'), // bn-ok
  checkAmount: (amount: string) => tr(`The amount is ${amount}`, `টাকার পরিমাণ ${amount}`), // bn-ok
  checkLast4: (l4: string) => tr(`Sent from a number ending ${l4}`, `যে নম্বর থেকে এসেছে তার শেষ ৪: ${l4}`), // bn-ok
  checksNeeded: () => trPlain('Tick all three after checking your wallet', 'ওয়ালেটে মিলিয়ে তিনটাতেই টিক দিন'), // bn-ok
  claimedAt: (when: string) => tr(`Told ${when}`, `জানিয়েছেন ${when}`), // bn-ok
  reconfirm: () => tr('Received after all', 'আসলে পেয়েছি'), // bn-ok
  confirmText: (amount: string, method: string, trx: string, last4: string) =>
    tr(`Check your ${method} first: ${amount}, TrxID ${trx}, from a number ending ${last4}. The patient gets a receipt.`, `আগে নিজের ${method}-এ মিলিয়ে নিন: ${amount}, TrxID ${trx}, যে নম্বরের শেষে ${num(last4)}। রোগী রসিদ পাবেন।`), // bn-ok
  confirmed: () => trPlain('Confirmed — receipt made', 'নিশ্চিত হয়েছে — রসিদ তৈরি'), // bn-ok
  rejectTitle: () => tr("Didn't get it?", 'টাকা পাননি?'), // bn-ok
  rejectText: () => tr('The patient will be asked to check and tell again.', 'রোগীকে মিলিয়ে আবার জানাতে বলা হবে।'), // bn-ok
  rejectWhy: () => tr('Why?', 'কেন?'), // bn-ok
  rejectNote: () => tr('Note (optional)', 'নোট (ঐচ্ছিক)'), // bn-ok
  rejectNoteReq: () => tr('Note', 'নোট'), // bn-ok
  rejectNotePh: () => tr('e.g. ৳400 came, not ৳500', 'যেমন: ৳৫০০ নয়, ৳৪০০ এসেছে'), // bn-ok
  rejectNeedNote: () => trPlain('Write a note for "Other"', '"অন্য কারণ" হলে নোট লিখুন'), // bn-ok
  rejected: () => trPlain('Told the patient', 'রোগীকে জানানো হয়েছে'), // bn-ok
  refund: () => tr('Refund', 'টাকা ফেরত'), // bn-ok
  refundTitle: () => tr('Send money back', 'টাকা ফেরত দিন'), // bn-ok
  refundHow: () => tr('First send the money back to the patient from your own bKash/Nagad, then write that TrxID here.', 'আগে নিজের বিকাশ/নগদ থেকে রোগীকে টাকা ফেরত পাঠান, তারপর সেই TrxID এখানে লিখুন।'), // bn-ok
  refundToSender: (l4: string) => tr(`Send it back to the number the money came from (last 4: ${l4}) — nowhere else.`, `যে নম্বর থেকে টাকা এসেছিল (শেষ ৪: ${l4}) সেখানেই ফেরত দিন — অন্য কোথাও নয়।`), // bn-ok
  refundAckNote: () => tr('The patient will be asked to confirm they got it.', 'রোগীকে জিজ্ঞেস করা হবে টাকা পেয়েছেন কি না।'), // bn-ok
  refundAmount: () => tr('Amount sent back', 'কত ফেরত দিলেন'), // bn-ok
  refundTrxLabel: () => tr('Refund TrxID', 'ফেরতের TrxID'), // bn-ok
  refundDone: () => trPlain('Refund saved. The patient will be told.', 'ফেরত লেখা হয়েছে। রোগীকে জানানো হবে।'), // bn-ok
  errRefundAmount: (max: string) => trPlain(`Write an amount up to ${max}`, `${max} পর্যন্ত টাকা লিখুন`), // bn-ok
  errRefundTrx: () => trPlain('Write the TrxID of the refund (6 to 20 letters and digits)', 'ফেরতের TrxID লিখুন (৬ থেকে ২০ অক্ষর)'), // bn-ok
  from: () => tr('From', 'কার কাছ থেকে'), // bn-ok
  filterAll: () => tr('All', 'সব'), // bn-ok
  noneHere: () => tr('No payments here', 'এখানে কোনো পেমেন্ট নেই'), // bn-ok
  csv: () => tr('Download CSV', 'CSV নামান'), // bn-ok
  csvMonth: () => tr('Month', 'মাস'), // bn-ok
  ago: (h: number) => tr(`${h} h ago`, `${num(h)} ঘণ্টা আগে`), // bn-ok
  late: () => tr('Waiting over 24 hours', '২৪ ঘণ্টার বেশি অপেক্ষায়'), // bn-ok
  // সেটিং
  methodsTitle: () => tr('Where patients send money', 'রোগীরা কোন নম্বরে টাকা পাঠাবেন'), // bn-ok
  methodsIntro: () => tr('Money comes straight to your own number. Lifolo never holds it.', 'টাকা সরাসরি আপনার নিজের নম্বরে আসে। Lifolo কখনো টাকা রাখে না।'), // bn-ok
  personalNote: () => tr('Your mobile operator may limit or freeze a personal account used for business. A merchant or Personal Retail Account (PRA) number is safer.', 'ব্যবসার কাজে পার্সোনাল অ্যাকাউন্ট ব্যবহার করলে মোবাইল ব্যাংকিং প্রতিষ্ঠান সীমা দিতে বা বন্ধ করতে পারে। মার্চেন্ট বা পার্সোনাল রিটেইল অ্যাকাউন্ট (PRA) নম্বর বেশি নিরাপদ।'), // bn-ok
  reauthTitle: () => tr('Log in again', 'আবার লগইন করুন'), // bn-ok
  reauthText: (email: string) => tr(`For safety, changing payment numbers needs a fresh login. We'll send a code to ${email}.`, `নিরাপত্তার জন্য পেমেন্টের নম্বর বদলাতে আবার লগইন লাগবে। ${email}-এ কোড পাঠানো হবে।`), // bn-ok
  sendCode: () => tr('Send code', 'কোড পাঠান'), // bn-ok
  codeLabel: () => tr('Code from the email', 'ইমেইলে আসা কোড'), // bn-ok
  verify: () => tr('Log in and save', 'লগইন করে সেভ করুন'), // bn-ok
  codeSent: () => trPlain('Code sent', 'কোড পাঠানো হয়েছে'), // bn-ok
  notMe: () => tr("I didn't do this", 'আমি করিনি'), // bn-ok
  notMeTitle: () => tr('Freeze payment numbers?', 'পেমেন্টের নম্বর আটকে দেবেন?'), // bn-ok
  notMeText: () => tr('No new payment requests until an admin checks. Other devices are logged out. Do this if you did not change the numbers.', 'অ্যাডমিন দেখা পর্যন্ত নতুন কোনো পেমেন্ট চাওয়া যাবে না। অন্য সব ডিভাইস থেকে লগআউট হবে। নম্বর আপনি না বদলে থাকলে এটা করুন।'), // bn-ok
  frozenToast: () => trPlain('Frozen. An admin will contact you.', 'আটকে দেওয়া হয়েছে। অ্যাডমিন যোগাযোগ করবেন।'), // bn-ok
  frozen: () => tr('Payment numbers are frozen until an admin checks', 'অ্যাডমিন না দেখা পর্যন্ত পেমেন্টের নম্বর আটকানো'), // bn-ok
  recentChanges: () => tr('Recent changes', 'সাম্প্রতিক বদল'), // bn-ok
  changeLine: (m: string, what: string, when: string) => tr(`${m}: ${what} · ${when}`, `${m}: ${what} · ${when}`),
  coolOff: () => tr('A changed number starts after 24 hours — until then patients see the old one. Requests already made keep their number. You get a notification for every change.', 'নম্বর বদলালে নতুনটা ২৪ ঘণ্টা পর চালু হবে — ততক্ষণ রোগীরা আগেরটাই দেখবেন। আগে তৈরি পেমেন্টে আগের নম্বরই থাকে। প্রতিটা বদলে আপনাকে নোটিফিকেশন পাঠানো হবে।'), // bn-ok
  pendingNumber: (num_: string, when: string) => tr(`New number ${num_} starts ${when}`, `নতুন নম্বর ${num_} চালু হবে ${when}`), // bn-ok
  use: () => tr('Take payments here', 'এই মাধ্যমে নিন'), // bn-ok
  accountType: () => tr('Account type', 'অ্যাকাউন্টের ধরন'), // bn-ok
  number: () => tr('Number', 'নম্বর'), // bn-ok
  errNumber: (m: string) => trPlain(`Enter a valid mobile number for ${m}`, `${m}-এর জন্য সঠিক মোবাইল নম্বর দিন`), // bn-ok
  errNoMethod: () => trPlain('Turn on at least one, or turn all off to stop taking payments', 'অন্তত একটা চালু করুন'), // bn-ok
  save: () => tr('Save', 'সেভ করুন'), // bn-ok
  saved: () => trPlain('Saved', 'সেভ হয়েছে'), // bn-ok
  ownerOnly: () => tr('Only the owner can change this', 'শুধু মালিক এটা বদলাতে পারেন'), // bn-ok
  advanceTitle: () => tr('Advance booking fee', 'বুকিংয়ে অগ্রিম ফি'), // bn-ok
  advanceIntro: () => tr('Optional. Patients pay this to your number when they book at that chamber. Online chambers use their own fee.', 'ঐচ্ছিক। ঐ চেম্বারে বুকিংয়ের সময় রোগীরা এটা আপনার নম্বরে পাঠাবেন। অনলাইন চেম্বারে নিজের ফি-ই চলে।'), // bn-ok
  advanceOn: () => tr('Take an advance', 'অগ্রিম নিন'), // bn-ok
  advanceAmount: () => tr('Advance (taka)', 'অগ্রিম (টাকা)'), // bn-ok
  refundableUntil: () => tr('Refund if cancelled before', 'কত আগে বাতিলে ফেরত'), // bn-ok
  hoursBefore: (h: number) => (h === 0 ? tr('No refund', 'ফেরত নেই') : tr(`${h} hours before`, `${num(h)} ঘণ্টা আগে`)), // bn-ok
  errAdvance: () => trPlain('Write the advance amount (1 to 5,000 taka)', 'অগ্রিমের টাকা লিখুন (১ থেকে ৫,০০০ টাকা)'), // bn-ok
  advanceNeedsMethod: () => tr('Add a payment number above first', 'আগে উপরে পেমেন্টের নম্বর দিন'), // bn-ok
  noChambers: () => tr('No chamber yet', 'এখনো কোনো চেম্বার নেই'), // bn-ok
  // ব্যবসা: পেমেন্ট চাওয়া
  askPay: () => tr('Ask for payment', 'পেমেন্ট চান'), // bn-ok
  askPayTitle: () => tr('Ask the patient to pay', 'রোগীর কাছে পেমেন্ট চান'), // bn-ok
  askPayText: () => tr('The patient sees your numbers and sends the money straight to you. Cash is fine too.', 'রোগী আপনার নম্বর দেখে সরাসরি আপনাকে টাকা পাঠাবেন। নগদেও নিতে পারেন।'), // bn-ok
  askPayAmount: () => tr('Amount (taka)', 'টাকা'), // bn-ok
  askPaySent: () => trPlain('The patient was asked to pay', 'রোগীর কাছে পেমেন্ট চাওয়া হয়েছে'), // bn-ok
  errAmount: () => trPlain('Write the amount', 'টাকার পরিমাণ লিখুন'), // bn-ok
  setMethodsFirst: () => tr('Set your payment numbers first', 'আগে পেমেন্টের নম্বর দিন'), // bn-ok
  // bKash গেটওয়ে (Lifolo-র নিজের আয়)
  pgwTitle: () => tr('Payment result', 'পেমেন্টের ফল'), // bn-ok
  pgwOk: () => tr('Payment successful', 'পেমেন্ট সফল হয়েছে'), // bn-ok
  pgwFail: () => tr('Payment failed', 'পেমেন্ট হয়নি'), // bn-ok
  pgwCancel: () => tr('Payment cancelled', 'পেমেন্ট বাতিল করা হয়েছে'), // bn-ok
  pgwFailText: () => tr('No money was taken. You can try again.', 'কোনো টাকা কাটা হয়নি। আবার চেষ্টা করতে পারেন।'), // bn-ok
  pgwHome: () => tr('Go to home', 'হোমে যান'), // bn-ok
};

/** মাধ্যমের নাম (ব্র্যান্ড), অ্যাপ আর ডায়াল কোড */
export const methodName = (m: PayMethodKind | string) =>
  m === 'bkash' ? tr('bKash', 'বিকাশ') : m === 'nagad' ? tr('Nagad', 'নগদ') : m === 'rocket' ? tr('Rocket', 'রকেট') : m === 'upay' ? tr('Upay', 'উপায়') : String(m); // bn-ok
export const methodCode = (m: PayMethodKind) => (m === 'bkash' ? '*247#' : m === 'nagad' ? '*167#' : m === 'rocket' ? '*322#' : '*268#');
/** personal → Send Money, merchant → Payment (অ্যাপের মেনুর নাম ইংরেজিতেই থাকে) */
export const methodOp = (t: AcctType) => (t === 'merchant' ? 'Payment' : 'Send Money');
export const acctText = (t: AcctType) => (t === 'merchant' ? T.merchant() : T.personal());

export function purposeText(p: PayPurpose | string): string {
  switch (p) {
    case 'chamber_fee': return tr('Consultation fee', 'পরামর্শের ফি'); // bn-ok
    case 'booking_advance': return tr('Booking advance', 'বুকিংয়ের অগ্রিম'); // bn-ok
    case 'delivery': return tr('Medicine order', 'ওষুধের অর্ডার'); // bn-ok
    case 'lab': return tr('Lab tests', 'পরীক্ষার টাকা'); // bn-ok
    case 'ambulance_fare': return tr('Ambulance fare', 'অ্যাম্বুলেন্সের ভাড়া'); // bn-ok
    case 'invoice_due': return tr('Due on receipt', 'রসিদের বাকি'); // bn-ok
    default: return T.sheetTitle();
  }
}
export const purposeIcon = (p: string) =>
  p === 'delivery' ? 'local_shipping' : p === 'lab' ? 'local_hospital' : p === 'ambulance_fare' ? 'ambulance' : p === 'invoice_due' ? 'list_alt' : 'stethoscope';

/** অবস্থা — রোগীর দিক থেকে */
export function statusText(st: PayStatus): string {
  switch (st) {
    case 'claimed': return tr('Being checked', 'যাচাই হচ্ছে'); // bn-ok
    case 'confirmed': return tr('Paid', 'পরিশোধিত'); // bn-ok
    case 'rejected': return tr('Not received', 'পাননি'); // bn-ok
    case 'expired': return tr('Expired', 'সময় শেষ'); // bn-ok
    case 'refund_due': return tr('Refund owed', 'ফেরত পাওনা'); // bn-ok
    case 'refund_claimed': return tr('Refund sent?', 'ফেরত পাঠিয়েছে বলছে'); // bn-ok
    case 'refunded': return tr('Refunded', 'ফেরত দেওয়া হয়েছে'); // bn-ok
    case 'cancelled': return tr('Cancelled', 'বাতিল'); // bn-ok
    default: return tr('Not sent', 'পাঠানো হয়নি'); // bn-ok
  }
}
/** তালিকায় ছোট অবস্থা: চেম্বারের খোলা অগ্রিম "অগ্রিম বাকি" */
export const statusShort = (st: PayStatus, purpose?: string, refundDue?: unknown) =>
  refundDue && st === 'confirmed' ? statusText('refund_due') : purpose === 'booking_advance' && (st === 'open' || st === 'rejected') ? T.advanceDue() : statusText(st);
/** অবস্থা — ব্যবসার দিক থেকে */
export function statusTextBiz(st: PayStatus): string {
  switch (st) {
    case 'claimed': return tr('Patient says sent — check', 'রোগী বলছেন পাঠিয়েছেন — মিলিয়ে নিন'); // bn-ok
    case 'refund_due': return tr('Refund owed', 'ফেরত দিতে হবে'); // bn-ok
    case 'refund_claimed': return tr('Refund sent — waiting for patient', 'ফেরত পাঠিয়েছেন — রোগীর অপেক্ষা'); // bn-ok
    case 'open': return tr('Not sent yet', 'এখনো পাঠাননি'); // bn-ok
    case 'rejected': return tr('You said not received', 'পাননি বলেছেন'); // bn-ok
    default: return statusText(st);
  }
}
/** "জানিয়েছেন" কখনো সবুজ/পরিশোধিতের মতো নয় (নিরাপত্তা পর্যালোচনা P1) — নিরপেক্ষ রং */
export const statusTone = (st: PayStatus): 'good' | 'primary' | 'error' | 'info' | undefined =>
  st === 'confirmed' ? 'good' : st === 'rejected' || st === 'refund_due' ? 'error' : st === 'refunded' || st === 'refund_claimed' || st === 'open' ? 'info' : undefined;

export function rejectText(r: RejectReason | string): string {
  switch (r) {
    case 'not_received': return tr('No money came', 'টাকা আসেনি'); // bn-ok
    case 'wrong_amount': return tr('Wrong amount', 'টাকার পরিমাণ মেলেনি'); // bn-ok
    case 'wrong_number': return tr('Sent to a different number', 'অন্য নম্বরে পাঠানো'); // bn-ok
    case 'other': return tr('Other', 'অন্য কারণ'); // bn-ok
    default: return r;
  }
}

/** রোগীর রিপোর্টের কারণ (pay_report) */
export const REPORT_REASONS = ['not_confirmed', 'asked_other_number', 'no_refund', 'wrong_number', 'asked_more', 'scam', 'other'] as const;
export function reportText(r: string): string {
  switch (r) {
    case 'not_confirmed': return tr("I paid, but they won't confirm", 'টাকা দিয়েছি, কিন্তু নিশ্চিত করছেন না'); // bn-ok
    case 'asked_other_number': return tr('Someone asked me to pay to another number', 'অন্য নম্বরে টাকা পাঠাতে বলেছে'); // bn-ok
    case 'no_refund': return tr("I didn't get the refund", 'ফেরত পাইনি'); // bn-ok
    case 'wrong_number': return tr('The number was wrong or not theirs', 'নম্বর ভুল বা তাঁদের নয়'); // bn-ok
    case 'asked_more': return tr('They asked for more than agreed', 'কথার চেয়ে বেশি টাকা চেয়েছেন'); // bn-ok
    case 'scam': return tr('Looks like a scam', 'প্রতারণা মনে হচ্ছে'); // bn-ok
    default: return tr('Other', 'অন্য কারণ'); // bn-ok
  }
}

export function stepText(k: StepKind, biz: string): string {
  switch (k) {
    case 'created': return tr('Payment asked', 'পেমেন্ট চাওয়া হয়েছে'); // bn-ok
    case 'claimed': return tr('You told it was sent', 'আপনি পাঠিয়েছেন জানিয়েছেন'); // bn-ok
    case 'confirmed': return tr(`${biz} confirmed`, `${biz} নিশ্চিত করেছেন`); // bn-ok
    case 'rejected': return tr(`${biz} didn't get it`, `${biz} পাননি`); // bn-ok
    case 'refund_due': return tr('Refund owed', 'ফেরত পাওনা'); // bn-ok
    case 'refunded': return tr('Money sent back', 'টাকা ফেরত পাঠানো হয়েছে'); // bn-ok
    case 'refund_ack': return tr('You got the refund', 'আপনি ফেরত পেয়েছেন'); // bn-ok
    case 'expired': return tr('Time ran out', 'সময় পেরিয়ে গেছে'); // bn-ok
    case 'cancelled': return tr('Cancelled', 'বাতিল'); // bn-ok
    default: return '';
  }
}

export const kindText = (k: BizKind | string) =>
  k === 'pharmacy' ? tr('Pharmacy', 'ফার্মেসি') : k === 'lab' ? tr('Lab', 'প্যাথলজি') : k === 'chamber' ? tr('Chamber', 'চেম্বার') : k === 'ambulance' ? tr('Ambulance', 'অ্যাম্বুলেন্স') : String(k); // bn-ok
export const kindIcon = (k: string) => (k === 'pharmacy' ? 'storefront' : k === 'lab' ? 'local_hospital' : k === 'chamber' ? 'stethoscope' : 'ambulance');

/** সার্ভারের কোড → বাংলা লেখা (নইলে সার্ভারের লেখাই) */
export function payErrText(code: string | undefined, method: PayMethodKind, fallback: string): string {
  switch (code) {
    case 'pay_ref_used': return T.trxUsed();
    case 'pay_ref_rejected': return T.trxRejected();
    case 'no_methods':
    case 'pay_no_methods': return T.noMethods();
    case 'trx_format': return T.errTrx(method);
    case 'last4': return T.errLast4();
    case 'pay_claim_limit': return T.oneOpenClaim();
    case 'expired': return T.expiredErr();
    case 'pay_frozen': return T.frozen();
    case 'pay_paused': return T.paused();
    case 'already_paid': return T.alreadyPaid();
    case 'no_amount':
    case 'nothing_due': return T.noAmount();
    case 'not_finished': return T.notFinished();
    case 'checks_required': return T.checksNeeded();
    case 'reauth_required': return T.reauthTitle();
    case 'owner_only': return T.ownerOnly();
    case 'pay_claim_rate': return T.claimRate();
    case 'pay_claim_day_rate':
    case 'pay_user_rate': return T.claimDayRate();
    case 'pay_one_open_claim':
    case 'pay_claim_limited': return T.oneOpenClaim();
    case 'pay_expired': return T.expiredErr();
    case 'pay_trx_invalid':
    case 'bad_trx': return T.errTrx(method);
    case 'no_payment_blood': return T.noBlood();
    case 'pgw_unavailable': return T.pgwOff();
    default: return fallback;
  }
}
