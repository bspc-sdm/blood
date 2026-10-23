package com.lifolo.pharmacy

import com.lifolo.common.Lang

/**
 * "ওষুধ খুঁজুন" — how much the buyer needs (backend/docs/MEDFIND.md). The units fit the drug's form (DGDA list):
 * tablet/capsule → পাতা (strip, default) or পিস; syrup/suspension → বোতল; injection → টি/ভায়াল; anything else → টি.
 * The same rules live in the apps (Android MedQty, web medfind.ts) — keep the three in step.
 */
object MedQty {
    /** what a buyer may ask for */
    const val MAX = 999

    /** what a shop may say it has */
    const val HAVE_MAX = 9999

    /** unit codes on the wire and in med_finds.unit */
    val ALL = listOf("strip", "pcs", "bottle", "vial", "unit")

    /** the units a buyer may choose for this form; the first is the default */
    fun unitsFor(form: String?): List<String> {
        val f = (form ?: "").lowercase()
        return when {
            f.contains("inject") || f.contains("infusion") || f.contains("vaccine") -> listOf("vial")
            f.contains("tablet") || f.contains("capsule") || f.contains("cozycap") -> listOf("strip", "pcs")
            f.contains("nebuliser") || f.contains("respirator") -> listOf("unit")
            f.contains("syrup") || f.contains("suspension") || f.contains("elixir") || f.contains("linctus") || f.contains("oral solution") -> listOf("bottle")
            else -> listOf("unit")
        }
    }

    /** the unit to store: the asked one when it fits the form, else the form's default */
    fun unitFor(form: String?, asked: String?): String = unitsFor(form).let { if (asked in it) asked!! else it[0] }

    /** "৩ পাতা", "২টি/ভায়াল", "১০ পিস" (Bangla digits) */
    fun bn(qty: Int, unit: String?): String {
        val n = Lang.bnDigits(qty.toString())
        return when (unit) {
            "strip" -> "$n পাতা" // bn-ok
            "pcs" -> "$n পিস" // bn-ok
            "bottle" -> "$n বোতল" // bn-ok
            "vial" -> "${n}টি/ভায়াল" // bn-ok
            else -> "${n}টি" // bn-ok
        }
    }

    /** "3 strips", "1 bottle", "2 vials", "10 pcs" */
    fun en(qty: Int, unit: String?): String {
        val one = qty == 1
        return "$qty " + when (unit) {
            "strip" -> if (one) "strip" else "strips"
            "bottle" -> if (one) "bottle" else "bottles"
            "vial" -> if (one) "vial" else "vials"
            else -> if (one) "pc" else "pcs"
        }
    }

    fun text(lang: String?, qty: Int, unit: String?): String = Lang.pick(lang, en(qty, unit), bn(qty, unit))
}
