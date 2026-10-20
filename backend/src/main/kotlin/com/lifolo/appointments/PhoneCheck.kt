package com.lifolo.appointments

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.LifoloProperties
import com.lifolo.common.Phones
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * phone.js enabled / isVerified / requireVerified (the SMS actions themselves are P6): phone verification is on when
 * SMS_URL_TEMPLATE is set; a number is verified when `user_state.verified_phone` equals the profile phone.
 */
@Component
class PhoneCheck(private val jdbc: JdbcTemplate, private val props: LifoloProperties) {
    fun enabled(): Boolean = props.smsUrlTemplate.isNotEmpty()

    fun isVerified(uid: String): Boolean {
        if (!enabled()) return true
        val phone = Phones.normalizeBd(jdbc.queryForList("SELECT phone FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull())
        val verified = jdbc.queryForList("SELECT verified_phone FROM user_state WHERE uid = ?", String::class.java, uid).firstOrNull()
        return phone != null && verified == phone
    }

    fun requireVerified(lang: String?, uid: String) {
        if (!isVerified(uid)) {
            throw ApiException(403, Lang.tr(lang, "Verify your phone number first", "আগে আপনার ফোন নম্বর যাচাই করুন"), "phone_unverified")
        }
    }
}
