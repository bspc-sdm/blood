# Lifolo release (R8): শুধু অব্যবহৃত কোড বাদ — নাম বদলানো হয় না (Sentry-র স্ট্যাক ট্রেস পড়া যায়, reflection-এ ভয় কম)।
# লাইব্রেরিগুলোর নিজের নিয়ম (Firebase, Sentry, LiveKit/WebRTC, play-services) AAR-এর consumer rules থেকে আসে।
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# অ্যাপের নিজের ক্লাস (activity/service/receiver manifest থেকে এমনিতেই থাকে) — সাবধানে সব রাখা
-keep class com.bspc.blood.** { *; }

# LiveKit/WebRTC: JNI থেকে ডাকা ক্লাস (consumer rules-এর পাশাপাশি নিশ্চিত করা)
-keep class livekit.org.webrtc.** { *; }
-keep class com.twilio.audioswitch.** { *; }

# লাইব্রেরির ঐচ্ছিক নির্ভরতা (না থাকলে R8 থামে না)
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.**
-dontwarn javax.sip.**
-dontwarn gov.nist.**
-dontwarn android.gov.nist.**
