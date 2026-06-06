package org.cubexmc.ecobalancer.tax

class TaxRunState(
    val isRunning: Boolean,
    val operationId: Int,
    val policyName: String?,
    val startedAt: Long,
    val totalPlayers: Int,
    val processedPlayers: Int,
    val affectedPlayers: Int,
    val totalDeducted: Double,
    val trigger: TaxOperationType?,
    val senderName: String?,
)
