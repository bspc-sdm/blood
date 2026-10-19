package com.lifolo.blood

/**
 * Blood donation: requests, responses, donors, emergency calls, blood banks, date corrections, hospitals list for requests.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * request_create, request_reopen, request_delete, blood_request_get, blood_requests_mine, blood_requests_near, blood_response_get, blood_responses_list, blood_contact_get, report_request, response_update, confirm_donor, donation_date_set, guest_requests, emergencies_active, emergency_respond, emergency_donated, bloodbanks, bloodbank_*; HTTP: /api/notify-request, /api/respond, /api/close-request, /api/edit-request, /api/admin/corrections, /api/admin/resolve
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object BloodModule
