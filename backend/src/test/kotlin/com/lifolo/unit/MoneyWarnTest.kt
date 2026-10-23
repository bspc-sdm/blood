package com.lifolo.unit

import com.lifolo.chat.MoneyWarn
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** payments security review P2: a phone-like number + money words in a chat message → warn "money_number" */
class MoneyWarnTest {
    @Test
    fun `a number with money words warns, either alone does not`() {
        for (t in listOf(
            "এই নম্বরে ৫০০ টাকা বিকাশ করুন 01712345678",
            "Send money to 0171-234-5678 please",
            "নগদ: ০১৭১২ ৩৪৫ ৬৭৮",
            "bkash +880 1712 345678",
            "৳200 পাঠান 01812.345.678",
        )) assertTrue(MoneyWarn.check(t), t)
        for (t in listOf(
            "আমার নম্বর 01712345678, কল দিন",
            "৫০০ টাকা লাগবে",
            "বিকাশ নম্বর অ্যাপে দেওয়া আছে",
            "রিপোর্ট 12345 টাকা",
        )) assertFalse(MoneyWarn.check(t), t)
    }

    @Test
    fun `stronger wording in blood threads`() {
        assertTrue(MoneyWarn.text("blood", "bn").contains("রক্তের জন্য কোনো টাকা দিতে হয় না"))
        assertTrue(MoneyWarn.text("rx_order", "bn").contains("অ্যাপের পেমেন্ট পাতা ছাড়া"))
        assertTrue(MoneyWarn.text("blood", "en").startsWith("Nobody has to pay for blood"))
    }
}
