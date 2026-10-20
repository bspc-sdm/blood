package com.bspc.blood

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/*
 * এই প্যাকেজের সব runCatching এখানকারটা (একই প্যাকেজের ঘোষণা Kotlin-এর নিজের kotlin.runCatching-এর আগে বসে)।
 * তফাত একটাই: কোরুটিন বাতিল (CancellationException) ধরা হয় না, ছেড়ে দেওয়া হয়।
 *
 * কেন: পাতা বদলালে বা LaunchedEffect-এর key বদলালে আগের লোড বাতিল হয়। stdlib-এর runCatching সেই বাতিলকে
 * "ব্যর্থ" ধরত — onFailure-এ errorMsg বসত (যেমন হোমে "রিকোয়েস্ট লোড হয়নি"), loading = false হত, আর তা ঘটত নতুন
 * (সফল) লোড শুরু হওয়ার পরে, তাই সার্ভার 200 দিলেও ভুলের লেখা থেকে যেত। বাতিল কোনো ভুল নয়।
 *
 * withTimeout-এর সময় শেষ (TimeoutCancellationException) আগের মতোই ব্যর্থতা — কোড সেটাকে "সময় পেরোল" হিসেবে সামলায়।
 */
inline fun <R> runCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: Throwable) {
    if (e is CancellationException && e !is TimeoutCancellationException) throw e
    Result.failure(e)
}

/** উপরেরটার receiver-সহ রূপ (kotlin.runCatching-এর T.runCatching-এর জায়গায়) */
inline fun <T, R> T.runCatching(block: T.() -> R): Result<R> = try {
    Result.success(block())
} catch (e: Throwable) {
    if (e is CancellationException && e !is TimeoutCancellationException) throw e
    Result.failure(e)
}
