package com.lifolo.ambulance

/**
 * Ambulance listing, calls/ratings, owner stats, "call an ambulance" requests with fares, destinations.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * ambulances, ambulance_mine, ambulance_save, ambulance_available, ambulance_delete, ambulance_called, ambulance_rate, ambulance_stats, ambreq_*, amb_facilities, maps_link_resolve
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object AmbulanceModule
