package com.lifolo.contracts

import org.springframework.stereotype.Component

/** TODO(P1): not ported — requestend.js */
@Component
class RequestEndStub : RequestEnd {
    override fun notifyEnded(requestId: String, hospital: String, patientGroup: String, reason: String, donorUids: List<String>?): Int =
        throw NotImplementedError("TODO(P1): requestend.notifyEnded")

    override fun donorLeft(requestId: String, uid: String): Unit = throw NotImplementedError("TODO(P1): requestend.donorLeft")

    override fun releaseOthers(uid: String, exceptRequestId: String): Unit = throw NotImplementedError("TODO(P1): requestend.releaseOthers")
}
