package com.lifolo.demo

import com.lifolo.common.ApiException
import com.lifolo.common.Lang

/**
 * Demo businesses are public (one-click login) and their receipts verify on lifolo.com/v — so their name can't be
 * changed, or anyone could print "verified" receipts under any name (security review I9, docs/INVOICES.md).
 */
object DemoLock {
    fun checkRename(lang: String?, uid: String?, renamed: Boolean) {
        if (renamed && DemoWorld.isDemoUid(uid)) {
            throw ApiException(403, Lang.tr(lang, "The demo business's name can't be changed", "ডেমো প্রতিষ্ঠানের নাম বদলানো যায় না"), "demo_locked") // bn-ok
        }
    }
}
