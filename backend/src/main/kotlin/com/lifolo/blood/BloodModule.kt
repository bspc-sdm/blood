package com.lifolo.blood

import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.stereotype.Component

/**
 * Blood donation actions of /api/community (P1): requests, responses, donors, donation date, emergency appeals,
 * blood banks. The legacy endpoints are in [BloodHttpController], share pages in [ShareController].
 */
@Component
class BloodActions(
    private val requests: RequestService,
    private val donors: DonorService,
    private val emergencies: EmergencyService,
    private val banks: BloodBankService,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        // requestcreate.js, myrequests.js
        "request_create" to ActionHandler { requests.create(it) },
        "request_reopen" to ActionHandler { requests.reopen(it) },
        "request_delete" to ActionHandler { requests.delete(it) },
        // clientdata.js
        "blood_request_get" to ActionHandler { requests.get(it) },
        "blood_requests_mine" to ActionHandler { requests.mine(it) },
        "blood_requests_near" to ActionHandler { requests.near(it) },
        "blood_response_get" to ActionHandler { requests.responseGet(it) },
        "blood_responses_list" to ActionHandler { requests.responsesList(it) },
        "blood_contact_get" to ActionHandler { requests.contactGet(it) },
        "report_request" to ActionHandler { requests.report(it) },
        // guest.js
        "guest_requests" to ActionHandler { requests.guestRequests(it) },
        // donors.js, donationdate.js
        "response_update" to ActionHandler { donors.responseUpdate(it) },
        "confirm_donor" to ActionHandler { donors.confirmDonor(it) },
        "donation_date_set" to ActionHandler { donors.donationDateSet(it) },
        // emergency.js
        "emergencies_active" to ActionHandler { emergencies.active(it) },
        "emergency_respond" to ActionHandler { emergencies.respond(it) },
        "emergency_donated" to ActionHandler { emergencies.donated(it) },
        "admin_emergency_create" to ActionHandler { emergencies.adminCreate(it) },
        "admin_emergency_more" to ActionHandler { emergencies.adminMore(it) },
        "admin_emergency_group" to ActionHandler { emergencies.adminGroup(it) },
        "admin_emergency_close" to ActionHandler { emergencies.adminClose(it) },
        "admin_emergencies" to ActionHandler { emergencies.adminList(it) },
        // bloodbank.js
        "bloodbanks" to ActionHandler { banks.banks(it) },
        "bloodbank_mine" to ActionHandler { banks.mine(it) },
        "bloodbank_save" to ActionHandler { banks.save(it) },
        "bloodbank_delete" to ActionHandler { banks.delete(it) },
        "bloodbank_called" to ActionHandler { banks.called(it) },
        "bloodbank_stats" to ActionHandler { banks.ownStats(it) },
        // LISTINGS.md: the owner's stock per blood group
        "bloodbank_stock_set" to ActionHandler { banks.stockSet(it) },
        "admin_bloodbanks" to ActionHandler { banks.adminList(it) },
        "admin_bloodbank_resolve" to ActionHandler { banks.adminResolve(it) },
        "admin_bloodbank_add" to ActionHandler { banks.adminAdd(it) },
        "admin_bloodbank_edit" to ActionHandler { banks.adminEdit(it) },
        "admin_bloodbank_delete" to ActionHandler { banks.adminDelete(it) },
        "admin_districts" to ActionHandler { banks.adminDistricts(it) },
    )
}
