package com.lifolo.common

/**
 * Published when an account loses access at once: deactivated by the person (account_deactivate), disabled by an admin.
 * Listeners cut live sessions that the login check alone does not stop (in-app calls: CallService.revokeFor).
 */
data class AccountCutOffEvent(val uid: String, val reason: String)
