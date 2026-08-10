package org.cubexmc.contract.service

import org.cubexmc.contract.model.ContractStatus

object SchedulingRules {
    const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

    @JvmStatic
    fun expiryAt(createdAt: Long, publishAt: Long?, days: Int): Long =
        (publishAt ?: createdAt) + days * DAY_MILLIS

    @JvmStatic
    fun shouldActivate(status: ContractStatus, publishAt: Long?, now: Long): Boolean =
        status == ContractStatus.SCHEDULED && publishAt != null && publishAt <= now
}
