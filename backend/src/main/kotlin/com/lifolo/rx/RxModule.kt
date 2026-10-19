package com.lifolo.rx

/**
 * Prescriptions, doctor assistants, paper patients, pharmacy prescription orders and dispensing.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * rx_me, rx_team, rx_team_set, rx_assisting, rx_patients, rx_patient_history, rx_patient_add, rx_create, rx_cancel, rx_doctor_list, rx_mine, rx_claim, rx_get, rx_activity, rx_pharmacy_open, rx_dispense, rx_pharmacy_options, rx_shop_find, rx_send_pharmacy, rx_order_withdraw, rx_orders_pharmacy, rx_order_update, rx_pharmacy_home, rx_pharmacy_online_set, rx_missed_clear
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object RxModule
