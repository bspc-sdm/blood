package com.lifolo.content

/**
 * Health Q&A, tips, pick-list catalog, hospitals directory, share pages (/r, /rx, /shop, assetlinks).
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * me, questions, question, ask, answer, edit_question, edit_answer, delete_question, delete_answer, tips, post_tip, edit_tip, delete_tip, catalog_get, hospitals, hospital_request; HTTP: /api/share
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object ContentModule
