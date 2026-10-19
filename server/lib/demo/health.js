// ডেমো ডেটা — ভাগ: health (দেখুন index.js)
// স্বাস্থ্য আলোচনা (প্রশ্ন-উত্তর), হেলথ টিপস, পেশাদার ব্যাজ, রিপোর্ট (মডারেশন), কেয়ারগিভার, নোটিফিকেশন,
// সাহায্য ও আপিল (টিকিট), পুরোনো "তারিখ সংশোধন"-এর অনুরোধ।
// সবশেষে চলে (blood → services → rx → health): প্রোফাইল blood ভাগ বানায়, এখানে শুধু merge।
// ওষুধের অ্যালার্ম ফোনেই থাকে (MedAlarms, SharedPreferences) — সার্ভারে কিছু নেই, তাই এখানে বানানো যায় না।
const { db, FieldValue } = require('../firebase');
const R = require('../rules');
const DIST = require('../districts').find((d) => d.en === 'Gazipur').bn;

// যাচাইয়ের কাগজের নমুনা ছবি (JPEG, "DEMO CERTIFICATE — NOT REAL" লেখা)
const CERT_JPEG = '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';

module.exports = {
  async seed(ctx) {
    const { u, now, day } = ctx;
    const HR = 3600 * 1000;
    const today = R.todayDhakaUtcMidnight(now);

    // ---------- পেছনের ব্যবহারকারী (লগইন নেই) ----------
    const bg = async (key, name, phone, extra = {}) => ctx.extraUser(key, { name, phone, placeName: DIST, lang: 'bn', ...extra });
    const nutri = await bg('h-nutri', 'ডেমো পুষ্টিবিদ ফারহানা', '01700000401');
    const trainer = await bg('h-trainer', 'ডেমো ফিটনেস ট্রেইনার মিঠু', '01700000402');
    const expert = await bg('h-expert', 'ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল', '01700000403');
    const mother = await bg('h-mother', 'ডেমো রোগী আমেনা খাতুন', '01700000404');
    const asker = await bg('h-asker', 'ডেমো প্রশ্নকারী সাকিব', '01700000405');
    const spam = await bg('h-spam', 'ডেমো হারবাল সেন্টার', '01700000406');
    const oldDonor = await bg('h-olddonor', 'ডেমো ডোনার মাসুদ', '01700000407', { lastDonationMillis: today - 40 * day });

    // ---------- আগের ভাগগুলো যা বানিয়েছে তা দেখে নেওয়া (ব্যাজ, পরিবার) ----------
    await ctx.flush();
    const [docBadge, nurseBadge, patientDoc] = await Promise.all([
      db.doc(`badges/${u.doctor.uid}`).get(),
      db.doc(`badges/${u.homecare.uid}`).get(),
      db.doc(`users/${u.patient.uid}`).get(),
    ]);
    const nameOf = (key) => u[key].name;

    // ---------- পেশাদার ব্যাজ ----------
    const approved = (type, checks, at) => ({ status: 'approved', reviewedAt: at, reviewedBy: 'demo', verification: { by: 'demo', byEmail: '', at, checks } });
    // ডাক্তার আর নার্সের ব্যাজ অন্য ভাগ না বানালে এখানে (নইলে উত্তর/টিপস দেওয়া যায় না)
    if (!docBadge.exists) {
      await ctx.set(`badges/${u.doctor.uid}`, {
        type: 'doctor', regNo: 'ডেমো-A-12345', workplace: 'ডেমো জয়দেবপুর জেনারেল হাসপাতাল, গাজীপুর', name: nameOf('doctor'),
        email: u.doctor.email, createdAt: now - 40 * day, ...approved('doctor', ['bmdc_found', 'bmdc_name', 'bmdc_active', 'workplace'], now - 39 * day),
      });
    }
    if (!nurseBadge.exists) {
      await ctx.set(`badges/${u.homecare.uid}`, {
        type: 'nurse', regNo: 'ডেমো-BNMC-4521', workplace: 'ডেমো ছায়াবীথি নার্সিং হোম, গাজীপুর', name: nameOf('homecare'),
        email: u.homecare.email, createdAt: now - 35 * day, docsHave: { cert: now - 35 * day }, ...approved('nurse', ['cert_no', 'cert_name', 'cert_valid', 'workplace'], now - 34 * day),
      });
    }
    // স্বাস্থ্য বিশেষজ্ঞ (অনুমোদিত) — উত্তর আর টিপস দেন; তাঁর একটা উত্তর রিপোর্ট হয়েছে
    await ctx.set(`badges/${expert}`, {
      type: 'expert', regNo: 'ডেমো-EXP-0007', workplace: 'ডেমো জনস্বাস্থ্য প্রকল্প, জয়দেবপুর', name: 'ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল',
      email: '', createdAt: now - 30 * day, docsHave: { cert: now - 30 * day }, ...approved('expert', ['cert_name', 'cert_relevant', 'workplace'], now - 29 * day),
    });
    // পুষ্টিবিদ (অপেক্ষায়, সনদের ছবিসহ) — মালিক ওয়েব প্যানেলে ছবি দেখে টিক দিয়ে অনুমোদন দেবেন
    await ctx.set(`badges/${nutri}`, {
      type: 'nutritionist', regNo: 'ডেমো-NUT-0042', workplace: 'ডেমো পুষ্টি পরামর্শ কেন্দ্র, জয়দেবপুর, গাজীপুর', name: 'ডেমো পুষ্টিবিদ ফারহানা',
      email: '', status: 'pending', createdAt: now - 20 * HR, docsHave: { cert: now - 20 * HR },
    });
    await ctx.set(`verifyDocs/badge_${nutri}_cert`, { kind: 'badge', listingId: nutri, type: 'cert', ownerUid: nutri, data: CERT_JPEG, size: CERT_JPEG.length, at: now - 20 * HR });
    // ফিটনেস ট্রেইনার (বাতিল, কারণসহ) — তিনি আপিল করেছেন (নিচে টিকিট)
    await ctx.set(`badges/${trainer}`, {
      type: 'trainer', regNo: 'ডেমো-FIT-0310', workplace: 'ডেমো ফিটনেস ক্লাব, শিববাড়ি', name: 'ডেমো ফিটনেস ট্রেইনার মিঠু',
      email: '', status: 'rejected', statusReason: 'সনদের ছবি অস্পষ্ট, নাম পড়া যায় না', createdAt: now - 6 * day, reviewedAt: now - 5 * day, reviewedBy: 'demo',
      docsHave: { cert: now - 6 * day },
    });
    await ctx.set(`verifyDocs/badge_${trainer}_cert`, { kind: 'badge', listingId: trainer, type: 'cert', ownerUid: trainer, data: CERT_JPEG, size: CERT_JPEG.length, at: now - 6 * day });

    // ---------- স্বাস্থ্য আলোচনা: প্রশ্ন আর উত্তর ----------
    const answerPaths = {};
    const q = async (id, by, text, at, { anonymous = false, answers = [] } = {}) => {
      const path = `questions/${id}`;
      let last = 0;
      for (const a of answers) {
        const aPath = `${path}/answers/${a.id}`;
        await ctx.set(aPath, { uid: a.uid, authorName: a.name, badge: a.badge, text: a.text, createdAt: a.at });
        (answerPaths[a.uid] = answerPaths[a.uid] || []).push(aPath);
        last = Math.max(last, a.at);
      }
      await ctx.set(path, {
        uid: by.uid, authorName: by.name, anonymous, text, createdAt: at, answerCount: answers.length, ...(last ? { lastAnswerAt: last } : {}),
      });
    };
    const P = { uid: u.patient.uid, name: nameOf('patient') };
    const DOC = { uid: u.doctor.uid, name: nameOf('doctor'), badge: 'doctor' };
    const EXP = { uid: expert, name: 'ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল', badge: 'expert' };
    const ans = (id, who, text, at) => ({ id, ...who, text, at });

    await q('demo-q-diabetes', P, 'আমার টাইপ-২ ডায়াবেটিস আছে, সকালে-রাতে মেটফরমিন খাই। সামনে রোজা — রোজা রাখা কি নিরাপদ? সেহরি আর ইফতারে ওষুধ কীভাবে খাব?', now - 8 * day, {
      answers: [
        ans('demo-a-diab-doc', DOC, 'রক্তে শর্করা নিয়ন্ত্রণে থাকলে বেশিরভাগ টাইপ-২ রোগী রোজা রাখতে পারেন। মেটফরমিন সাধারণত ইফতার আর সেহরিতে ভাগ করে খাওয়া হয়, তবে ডোজ ঠিক করবেন আপনার ডাক্তার। দিনের বেলা শর্করা ৩.৯-এর নিচে (৭০) বা ১৬.৭-এর ওপরে (৩০০) গেলে সাথে সাথে রোজা ভাঙবেন। রোজার আগে একবার চেম্বারে দেখিয়ে নিন।', now - 7 * day),
        ans('demo-a-diab-exp', EXP, 'ইফতারে একসাথে বেশি মিষ্টি আর ভাজাপোড়া না খেয়ে খেজুর, পানি, ডাল-সবজি দিয়ে শুরু করুন। সেহরি একদম শেষ সময়ে খান, আর ইফতার থেকে সেহরি পর্যন্ত অন্তত ৮ গ্লাস পানি।', now - 6 * day),
      ],
    });
    await q('demo-q-sleep', P, 'কয়েক সপ্তাহ ধরে রাতে ঘুম আসে না, সকালে মাথা ভার থাকে। দোকান থেকে ঘুমের ওষুধ কিনে খাওয়া কি ঠিক হবে?', now - 36 * HR, {
      anonymous: true,
      answers: [ans('demo-a-sleep-doc', DOC, 'ডাক্তারের পরামর্শ ছাড়া ঘুমের ওষুধ খাবেন না — অভ্যাস হয়ে যেতে পারে। রাতে মোবাইল কম দেখুন, প্রতিদিন একই সময়ে ঘুমাতে যান, বিকেলের পর চা-কফি বাদ দিন। দুই সপ্তাহে উন্নতি না হলে চেম্বারে আসুন।', now - 3 * HR)],
    });
    await q('demo-q-fever', { uid: asker, name: 'ডেমো প্রশ্নকারী সাকিব' }, 'আমার ৪ বছরের বাচ্চার ৩ দিন ধরে জ্বর ১০২°, কাশিও আছে। প্যারাসিটামল দিচ্ছি। কখন হাসপাতালে নেওয়া দরকার?', now - 2 * day, {
      answers: [
        ans('demo-a-fever-doc', DOC, 'শ্বাসকষ্ট, খিঁচুনি, খেতে না পারা, প্রস্রাব কমে যাওয়া বা জ্বর ৩ দিনের বেশি থাকলে আজই শিশু বিশেষজ্ঞ দেখান। ডেঙ্গুর মৌসুম — রক্ত পরীক্ষা (CBC, NS1) করিয়ে নিন। প্যারাসিটামল ওজন অনুযায়ী ৬ ঘণ্টা পরপর দিন; আইবুপ্রোফেন বা অ্যাসপিরিন দেবেন না।', now - 30 * HR),
        // ভুল পরামর্শ — রিপোর্ট হয়েছে (নিচে), মালিক মুছবেন বা রেখে দেবেন
        ans('demo-a-fever-exp', EXP, 'জ্বর ৩ দিন থাকলে দোকান থেকে একটা অ্যান্টিবায়োটিক কিনে খাওয়ালেই সেরে যাবে, ডাক্তার দেখানোর দরকার নেই।', now - 20 * HR),
      ],
    });
    await q('demo-q-donate', { uid: u.donor.uid, name: nameOf('donor') }, 'রক্ত দেওয়ার পর দুই দিন একটু মাথা ঘোরে। এটা কি স্বাভাবিক? আবার কবে রক্ত দিতে পারব?', now - 5 * day, {
      answers: [ans('demo-a-donate-doc', DOC, 'রক্তদানের পর ১–২ দিন হালকা মাথা ঘোরা স্বাভাবিক। প্রচুর পানি খান, ডিম, কলিজা, কচু শাক, ডাল খান। ১২০ দিন (৪ মাস) পরপর রক্ত দিতে পারবেন। মাথা ঘোরা বেশি দিন থাকলে হিমোগ্লোবিন পরীক্ষা করান।', now - 4 * day)],
    });
    // উত্তর নেই — পরীক্ষক ডাক্তার/নার্স হয়ে উত্তর দেবেন
    await q('demo-q-knee', { uid: mother, name: 'ডেমো রোগী আমেনা খাতুন' }, 'বয়স ৬২, দুই হাঁটুতে ব্যথা, সিঁড়ি দিয়ে উঠতে কষ্ট হয়। বাসায় কোন ব্যায়াম করা যায়? কোন খাবার এড়িয়ে চলব?', now - 26 * HR);
    await q('demo-q-bedsore', { uid: u.caregiver.uid, name: nameOf('caregiver') }, 'আমার শাশুড়ি প্রায় সবসময় শুয়ে থাকেন। পিঠে লাল দাগ হচ্ছে — বেড সোর যেন না হয় সেজন্য কী করব?', now - 6 * HR);
    // বিজ্ঞাপন — দুজন রিপোর্ট করেছেন
    await q('demo-q-ad', { uid: spam, name: 'ডেমো হারবাল সেন্টার' }, 'গ্যারান্টি সহকারে ৭ দিনে ডায়াবেটিস চিরতরে ভালো করুন! ইনসুলিন লাগবে না। আমাদের হারবাল ওষুধ নিতে এখনই ফোন করুন ০১৭০০০০০৪০৬ — ডেমো হারবাল সেন্টার', now - 10 * HR);

    // অ্যাকাউন্ট মুছলে নিজের উত্তর খুঁজে মোছার তালিকা (আসল answer কাজের মতো)
    for (const [uid, paths] of Object.entries(answerPaths)) {
      await ctx.set(`serverState/${uid}`, { answerPaths: FieldValue.arrayUnion(...paths) }, { merge: true });
    }

    // ---------- হেলথ টিপস ----------
    const tip = (id, who, title, body, at) => ctx.set(`tips/${id}`, { uid: who.uid, authorName: who.name, badge: who.badge, title, body, createdAt: at });
    const NURSE = { uid: u.homecare.uid, name: nameOf('homecare'), badge: 'nurse' };
    await tip('demo-tip-dengue', DOC, 'ডেঙ্গু জ্বরে যা করবেন, যা করবেন না',
      'জ্বর হলে প্রথম দিনেই NS1 পরীক্ষা করান। প্রচুর পানি, ডাবের পানি, স্যালাইন খান। জ্বরে শুধু প্যারাসিটামল — ব্যথানাশক (আইবুপ্রোফেন, ডাইক্লোফেনাক) বা অ্যাসপিরিন খাবেন না। পেটে ব্যথা, বমি, দাঁতের মাড়ি বা নাক দিয়ে রক্ত পড়লে দেরি না করে হাসপাতালে যান। বাসার আশেপাশে জমে থাকা পানি ফেলে দিন।', now - 2 * day);
    await tip('demo-tip-bp', DOC, 'উচ্চ রক্তচাপ নিয়ন্ত্রণে ৫টি সহজ অভ্যাস',
      '১) তরকারিতে লবণ কমান, পাতে আলাদা লবণ নয়। ২) প্রতিদিন ৩০ মিনিট হাঁটুন। ৩) ধূমপান ও জর্দা বাদ দিন। ৪) ওষুধ নিজে নিজে বন্ধ করবেন না। ৫) মাসে অন্তত একবার রক্তচাপ মাপুন — শিববাড়ি বা জয়দেবপুর বাজারের যেকোনো ফার্মেসিতে মাপা যায়।', now - 6 * day);
    await tip('demo-tip-winter', DOC, 'শীতে শিশুর ঠান্ডা-কাশি: কখন ডাক্তার দেখাবেন',
      'নাক বন্ধে নরমাল স্যালাইন ড্রপ দিন, গরম পানির ভাপ নিন। বুকের দুধ ও তরল খাবার চালু রাখুন। শ্বাস দ্রুত হলে, বুক দেবে গেলে, ঠোঁট নীল হলে বা খেতে না পারলে সাথে সাথে ডাক্তার দেখান। কাশির সিরাপ নিজে কিনে দেবেন না।', now - 11 * day);
    await tip('demo-tip-insulin', NURSE, 'ইনসুলিন ঠিকভাবে রাখার নিয়ম',
      'না খোলা ইনসুলিন ফ্রিজের নিচের তাকে (২–৮°C) রাখুন, ফ্রিজারে নয়। খোলা কলম/ভায়াল ঘরের তাপমাত্রায় ২৮ দিন পর্যন্ত চলে — রোদ আর চুলার কাছ থেকে দূরে রাখুন। প্রতিবার সুই বদলান আর ইনজেকশনের জায়গা ঘুরিয়ে দিন।', now - 4 * day);
    await tip('demo-tip-donor-food', EXP, 'রক্তদানের আগে ও পরে কী খাবেন',
      'রক্তদানের আগের রাতে ভালো ঘুমান, খালি পেটে রক্ত দেবেন না। আগে-পরে প্রচুর পানি খান। আয়রনের জন্য কলিজা, ডিম, ডাল, কচু শাক, পালং শাক খান; সাথে লেবু বা আমলকী খেলে আয়রন ভালো শোষণ হয়। রক্ত দেওয়ার দিন ভারী কাজ ও রোদে বেশি ঘোরাঘুরি এড়িয়ে চলুন।', now - 8 * day);

    // ---------- রিপোর্ট (মডারেশন) ----------
    const report = (path, kind, reporterUid, snapshot, at) => ctx.set(`modReports/${path.replace(/\//g, '_')}__${reporterUid}`, {
      kind, targetPath: path, reporterUid, reason: '', snapshot, status: 'open', createdAt: at,
    });
    const adText = 'গ্যারান্টি সহকারে ৭ দিনে ডায়াবেটিস চিরতরে ভালো করুন! ইনসুলিন লাগবে না। আমাদের হারবাল ওষুধ নিতে এখনই ফোন করুন ০১৭০০০০০৪০৬ — ডেমো হারবাল সেন্টার';
    await report('questions/demo-q-ad', 'question', u.patient.uid, adText, now - 8 * HR);
    await report('questions/demo-q-ad', 'question', u.donor.uid, adText, now - 7 * HR);
    await report('questions/demo-q-fever/answers/demo-a-fever-exp', 'answer', u.patient.uid,
      'জ্বর ৩ দিন থাকলে দোকান থেকে একটা অ্যান্টিবায়োটিক কিনে খাওয়ালেই সেরে যাবে, ডাক্তার দেখানোর দরকার নেই।', now - 15 * HR);

    // ---------- কেয়ারগিভার ও পরিবার ----------
    // রোগী রহিম → কেয়ারগিভার রুমা (চালু): রুমা "ওঁর হয়ে খুলুন" দিয়ে প্রেসক্রিপশন/সিরিয়াল দেখেন
    await ctx.set(`careLinks/${u.patient.uid}_${u.caregiver.uid}`, {
      patientUid: u.patient.uid, caretakerUid: u.caregiver.uid, patientName: nameOf('patient'), caretakerName: nameOf('caregiver'),
      caretakerEmail: u.caregiver.email, status: 'active', createdAt: now - 6 * day, acceptedAt: now - 6 * day + 2 * HR,
    });
    // আমেনা খাতুন রুমাকে কেয়ারগিভার হতে বলেছেন (অপেক্ষায়) — রুমা "রাজি" / "না" চাপবেন
    await ctx.set(`careLinks/${mother}_${u.caregiver.uid}`, {
      patientUid: mother, caretakerUid: u.caregiver.uid, patientName: 'ডেমো রোগী আমেনা খাতুন', caretakerName: nameOf('caregiver'),
      caretakerEmail: u.caregiver.email, status: 'invited', createdAt: now - 22 * HR,
    });
    // রোগীর পরিবারের সদস্য (আগের ভাগ না দিয়ে থাকলে) — এঁদের নামে সিরিয়াল নেওয়া যায়
    if (!(patientDoc.exists && Array.isArray(patientDoc.get('family')) && patientDoc.get('family').length)) {
      await ctx.set(`users/${u.patient.uid}`, {
        family: [
          { id: 'm_demo01a', name: 'ডেমো মা রাবেয়া বেগম', relation: 'মা', age: 68, gender: 'female', phone: '01700000408' },
          { id: 'm_demo02b', name: 'ডেমো ছেলে তামিম', relation: 'ছেলে', age: 9, gender: 'male', phone: '' },
        ],
      }, { merge: true });
    }

    // ---------- সাহায্য ও আপিল (টিকিট) ----------
    const ticket = (id, uid, email, name, category, status, messages) => ctx.set(`tickets/${id}`, {
      uid, email, name, category, status, messages,
      lastFrom: messages[messages.length - 1].from, createdAt: messages[0].at, updatedAt: messages[messages.length - 1].at,
    });
    const pt = [u.patient.uid, u.patient.email, nameOf('patient')];
    // উত্তরের অপেক্ষায় — মালিক উত্তর দেবেন
    await ticket('demo-t-open', ...pt, 'appointment', 'open', [
      { from: 'user', text: 'ডা. ডেমো হাসানের কাছে সিরিয়ালের অনুরোধ পাঠিয়েছিলাম, দুই দিন হলো কোনো সাড়া নেই। অনুরোধটা কি পৌঁছেছে? অন্য ডাক্তারের কাছে নতুন করে নেব?', at: now - 26 * HR },
    ]);
    // উত্তর দেওয়া
    await ticket('demo-t-answered', ...pt, 'blood', 'answered', [
      { from: 'user', text: 'রক্তের রিকোয়েস্ট দিয়েছিলাম, কিন্তু ডোনারদের ফোন নম্বর দেখাচ্ছে না কেন?', at: now - 3 * day },
      { from: 'admin', text: 'ডোনার "রক্ত দেব" চাপার পরই তাঁর নম্বর আপনাকে দেখানো হয় — এটা ডোনারের নিরাপত্তার জন্য। কেউ রাজি হলে সাথে সাথে নোটিফিকেশন পাবেন। (ডেমো উত্তর)', at: now - 24 * HR, by: 'demo' },
    ]);
    // বন্ধ
    await ticket('demo-t-closed', ...pt, 'account', 'closed', [
      { from: 'user', text: 'প্রোফাইলের ছবি বদলাতে পারছি না, বারবার "ছবি আপলোড হয়নি" দেখাচ্ছে।', at: now - 12 * day },
      { from: 'admin', text: 'ছবিটা একটু ছোট করে (বা স্ক্রিনশট নিয়ে) আবার চেষ্টা করুন। অ্যাপ হালনাগাদ করে নিন। (ডেমো উত্তর)', at: now - 11 * day, by: 'demo' },
      { from: 'user', text: 'হয়েছে, ধন্যবাদ।', at: now - 11 * day + 3 * HR },
    ]);
    // ব্যাজ বাতিলের আপিল (পেছনের ব্যবহারকারী)
    await ticket('demo-t-appeal', trainer, '', 'ডেমো ফিটনেস ট্রেইনার মিঠু', 'badge', 'open', [
      { from: 'user', text: 'আমার ফিটনেস ট্রেইনার ব্যাজের আবেদন বাতিল হয়েছে ("সনদের ছবি অস্পষ্ট")। আমার কাছে পরিষ্কার সনদ আছে — কীভাবে আবার পাঠাব?', at: now - 18 * HR },
    ]);

    // ---------- পুরোনো "শেষ রক্তদানের তারিখ সংশোধন" অনুরোধ (অ্যাডমিনের "তারিখ" তালিকা) ----------
    // নতুন অ্যাপে আর এই অনুরোধ তৈরি হয় না (তারিখ নিজেই বদলানো যায়), তবে পুরোনোগুলো এখনো তালিকায় আসে
    await ctx.set('correctionRequests/demo-corr-masud', {
      uid: oldDonor, name: 'ডেমো ডোনার মাসুদ', currentMillis: today - 40 * day, requestedMillis: today - 150 * day,
      reason: 'আসলে আমি ৫ মাস আগে রক্ত দিয়েছিলাম, ভুল করে নতুন তারিখ দিয়ে ফেলেছি।', status: 'pending',
      createdAtMillis: now - 2 * day, createdAt: now - 2 * day,
    });

    // ---------- নোটিফিকেশন (ঘণ্টা আইকন) ----------
    // id: uid_উল্টো সময়_… — নতুনগুলো আগে (firebase.js addInbox-এর মতো)
    let n = 0;
    const inbox = (uid, title, body, data, at) => {
      const rev = String(9999999999999 - at).padStart(13, '0');
      return ctx.set(`inbox/${uid}_${rev}_dh${(n++).toString(36)}`, { uid, title, body, data, createdAt: at });
    };
    const pu = u.patient.uid;
    await inbox(pu, 'আপনার প্রশ্নের উত্তর এসেছে', 'ডাক্তারের পরামর্শ ছাড়া ঘুমের ওষুধ খাবেন না — অভ্যাস হয়ে যেতে পারে। রাতে মোবাইল কম দেখুন, প্রতিদিন একই সময়ে ঘুমাতে যান…', { type: 'answer', questionId: 'demo-q-sleep' }, now - 3 * HR);
    await inbox(pu, 'Lifolo টিমের উত্তর এসেছে', '"সাহায্য ও আপিল" খুলে দেখুন', { type: 'support', ticketId: 'demo-t-answered' }, now - 24 * HR);
    await inbox(pu, `${nameOf('caregiver')} এখন আপনার কেয়ারগিভার`, '', { type: 'care' }, now - 6 * day + 2 * HR);
    await inbox(pu, 'আপনার প্রশ্নের উত্তর এসেছে', 'ইফতারে একসাথে বেশি মিষ্টি আর ভাজাপোড়া না খেয়ে খেজুর, পানি, ডাল-সবজি দিয়ে শুরু করুন…', { type: 'answer', questionId: 'demo-q-diabetes' }, now - 6 * day);
    await inbox(pu, 'আপনার প্রশ্নের উত্তর এসেছে', 'রক্তে শর্করা নিয়ন্ত্রণে থাকলে বেশিরভাগ টাইপ-২ রোগী রোজা রাখতে পারেন…', { type: 'answer', questionId: 'demo-q-diabetes' }, now - 7 * day);
    // দেড় দিন আগে পর্যন্ত পড়া হয়েছে: ওপরের দুটো না-পড়া (মোটা অক্ষর, ঘণ্টায় সংখ্যা ২)
    await ctx.set(`serverState/${pu}`, { inboxSeenAt: now - 36 * HR }, { merge: true });

    await inbox(u.donor.uid, 'আপনার প্রশ্নের উত্তর এসেছে', 'রক্তদানের পর ১–২ দিন হালকা মাথা ঘোরা স্বাভাবিক…', { type: 'answer', questionId: 'demo-q-donate' }, now - 4 * day);

    const cg = u.caregiver.uid;
    await inbox(cg, 'ডেমো রোগী আমেনা খাতুন আপনাকে কেয়ারগিভার করতে চান', 'Lifolo → প্রোফাইল → কেয়ারগিভার থেকে রাজি হন', { type: 'care' }, now - 22 * HR);
    // রোগীর খবর কেয়ারগিভারের কাছেও যায় (careaccess.pushPatient-এর মতো: শিরোনামে রোগীর নাম)
    await inbox(cg, `${nameOf('patient')}: ${nameOf('doctor')} নতুন প্রেসক্রিপশন দিয়েছেন`, '', { type: 'care', asUid: pu }, now - 2 * day);
  },
};
