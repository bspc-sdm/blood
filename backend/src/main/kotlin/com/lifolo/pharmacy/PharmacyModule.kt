package com.lifolo.pharmacy

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.jobs.DailyTask
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Pharmacy listing, ratings, medicine finder (pharmacy.js, medfind.js) and the pharmacy side of prescriptions:
 * dispensing, orders, shop code, online settings, missed list, stock list (rxnet.js).
 */
@Component
class PharmacyModule(
    private val pharmacy: PharmacyService,
    private val medFind: MedFindService,
    private val rx: RxPharmacyService,
    private val invoices: com.lifolo.invoices.InvoiceCreators,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        // pharmacy.js
        "pharmacy_me" to ActionHandler { pharmacy.me(it) },
        "pharmacy_save" to ActionHandler { pharmacy.save(it) },
        "pharmacy_pause" to ActionHandler { pharmacy.pause(it) },
        "pharmacy_delete" to ActionHandler { pharmacy.delete(it) },
        "pharmacies" to ActionHandler { pharmacy.list(it) },
        "pharmacy_called" to ActionHandler { pharmacy.called(it) },
        "pharmacy_rate" to ActionHandler { pharmacy.rate(it) },
        "pharmacy_stats" to ActionHandler { pharmacy.stats(it) },
        "admin_pharmacies" to ActionHandler { pharmacy.adminList(it) },
        "admin_pharmacy_resolve" to ActionHandler { pharmacy.adminResolve(it) },
        // medfind.js
        "drug_search" to ActionHandler { medFind.drugSearch(it) },
        "med_find" to ActionHandler { medFind.medFind(it) },
        "med_finds_mine" to ActionHandler { medFind.mine(it) },
        "med_find_close" to ActionHandler { medFind.close(it) },
        "med_find_requests" to ActionHandler { medFind.requests(it) },
        "med_find_answer" to ActionHandler { medFind.answer(it) },
        "pharmacy_find_setting" to ActionHandler { medFind.findSetting(it) },
        // rxnet.js (pharmacy side)
        "rx_pharmacy_open" to ActionHandler { rx.open(it) },
        "rx_dispense" to ActionHandler { rx.dispense(it) },
        "rx_pharmacy_options" to ActionHandler { rx.options(it) },
        "rx_shop_find" to ActionHandler { rx.shopFind(it) },
        "rx_send_pharmacy" to ActionHandler { rx.send(it) },
        "rx_order_withdraw" to ActionHandler { rx.withdraw(it) },
        "rx_orders_pharmacy" to ActionHandler { rx.ordersForPharmacy(it) },
        // + the receipt at out/delivered (docs/INVOICES.md)
        "rx_order_update" to ActionHandler { invoices.rxOrderUpdate(it) },
        "rx_pharmacy_home" to ActionHandler { rx.home(it) },
        "rx_pharmacy_online_set" to ActionHandler { rx.onlineSet(it) },
        "rx_missed_clear" to ActionHandler { rx.missedClear(it) },
        "stock_get" to ActionHandler { rx.stockGet(it) },
        "stock_set" to ActionHandler { rx.stockSet(it) },
    )
}

/** daily cron step 14 (`privacy`): delivery address/phone of online orders closed more than 7 days ago */
@Component
class RxOrderPrivacyTask(private val contacts: RxOrderContacts) : DailyTask {
    override val name = "privacy"
    override val order = 14

    override fun run(nowMs: Long): Any = linkedMapOf("rxOrderContactsPurged" to contacts.purgeClosedContacts(nowMs))
}

/**
 * account.js, pharmacy part: at `delete_account` the pharmacy is hidden; 30 days later the user's ratings leave the
 * averages, their calls, medicine finds and own orders go, and their shop (listing, stats, ratings it got, stock,
 * missed list, books) is removed — order contacts stripped first.
 */
@Component
class PharmacyDeletionStep(
    private val jdbc: JdbcTemplate,
    private val pharmacy: PharmacyService,
    private val contacts: RxOrderContacts,
    private val books: com.lifolo.books.BooksStore,
) : AccountDeletionStep {
    private val log = LoggerFactory.getLogger(javaClass)
    override val order = 400

    @Transactional
    override fun hide(uid: String) {
        pharmacy.hideForDeletion(uid)
    }

    @Transactional
    override fun deleteNow(uid: String, email: String?) {
        try {
            pharmacy.removeRatingsBy(uid)
        } catch (e: Exception) {
            log.error("pharmacy ratings cleanup failed: {}", e.message)
        }
        jdbc.update("DELETE FROM pharmacy_calls WHERE uid = ?", uid)
        jdbc.update("DELETE FROM med_finds WHERE uid = ?", uid)
        jdbc.update("DELETE FROM rx_orders WHERE patient_uid = ?", uid)
        try {
            contacts.stripOrderContacts(uid)
        } catch (e: Exception) {
            log.error("rx order contact strip failed: {}", e.message)
        }
        jdbc.update("DELETE FROM pharmacy_stock WHERE pharmacy_uid = ?", uid)
        jdbc.update("DELETE FROM pharmacy_missed WHERE pharmacy_uid = ?", uid)
        books.deleteFor(uid)
        pharmacy.removeListing(uid)
    }
}
