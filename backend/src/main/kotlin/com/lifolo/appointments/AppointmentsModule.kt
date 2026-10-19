package com.lifolo.appointments

/**
 * Doctor/nurse chambers (providers), booking, appointment status, live chamber queue.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * providers, provider_me, provider_save, book, my_appointments, appointment_update, queue_today, queue_control, appointment_queue, appointment_late, appointment_late_reply
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object AppointmentsModule
