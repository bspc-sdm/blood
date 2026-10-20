package com.lifolo.lab

/**
 * Labs (pathology), price lists, test catalogue, lab orders, home sample collection, reports, doctor report reviews.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * lab_me, lab_save, lab_delete, labs, lab_prices_get, lab_prices_set, lab_prices_public, labtests_get, rx_lab_options, rx_send_lab, lab_orders, lab_open_code, lab_order_status, lab_home_confirm, lab_home_decline, lab_home_cancel, lab_home_change, lab_report, lab_order_get, lab_form, lab_ranges_set, review_settings, review_pay, reviews_doctor, review_confirm, review_done
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object LabModule
