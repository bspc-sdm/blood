package com.lifolo.homecare

/**
 * Home-visit doctors and nurses: list, calls, ratings, stats.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * home_providers, home_called, home_rate, home_stats
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object HomecareModule
