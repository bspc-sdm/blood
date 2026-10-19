package com.lifolo.pharmacy

/**
 * Pharmacy listing, ratings, stock list, medicine finder.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * pharmacy_me, pharmacy_save, pharmacy_pause, pharmacy_delete, pharmacies, pharmacy_called, pharmacy_rate, pharmacy_stats, stock_get, stock_set, drug_search, med_find, med_finds_mine, med_find_close, med_find_requests, med_find_answer, pharmacy_find_setting
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object PharmacyModule
