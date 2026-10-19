package com.lifolo.contracts

/*
 * Contracts owned by P1 (blood donation). The stub in BloodStub.kt compiles and boots; nothing calls it before P6-rest
 * (account deletion). P1 replaces it (delete BloodStub.kt, add its service).
 */

/**
 * requestend.js — telling donors who said "I'll come" that a request ended, and keeping the counts right when an
 * agreed donor goes away. Consumer: P6 account deletion (P1 may also call these from its own AccountDeletionStep).
 */
interface RequestEnd {
    /**
     * notifyEnded(requestId, r, reason, donorIds): push to the donors still "accepted" on the request (or [donorUids]
     * when the list was read before deleting). [reason]: closed | expired | notNeeded | reopened | removed (texts of
     * requestend.js TEXT; unknown → closed). [hospital] and [patientGroup] fill the texts. Data: removed →
     * `{type: "request_removed"}`, else `{requestId, type: "request"}`. Returns how many were reached.
     */
    fun notifyEnded(requestId: String, hospital: String, patientGroup: String, reason: String, donorUids: List<String>? = null): Int

    /**
     * donorLeft(requestId, uid) = withdraw(…, "deleted"): the donor deleted their account — their response row is
     * deleted; if they had accepted: `accepted_count − 1`, confirmed count / fulfilled recomputed when they were
     * confirmed, and the requester (if the request is still active) gets "A donor is no longer available".
     */
    fun donorLeft(requestId: String, uid: String)

    /**
     * releaseOthers(uid, exceptRequestId): the donor gave blood for one request — their other "accepted" responses
     * (≤ 20, from `user_commitments`) become cancelled (`donated_elsewhere`) with a push to each requester, and the
     * commitments are cleared. Never throws.
     */
    fun releaseOthers(uid: String, exceptRequestId: String)
}
