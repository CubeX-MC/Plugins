package org.cubexmc.ecobalancer.policies

import java.util.function.Function
import kotlin.math.max

class TaxPolicy {
    var name: String? = null
    var description: String? = null
    var scheduleType: String? = null
    var checkTime: String = "00:00"
    var scheduleDaysOfWeek: List<Int> = ArrayList()
    var scheduleDatesOfMonth: List<Int> = ArrayList()
    var maxDeductionPerPlayer: Double = 0.0
    var minBalanceProtection: Double = 100.0
    var isOnlyOfflinePlayers: Boolean = false
    var inactiveDaysToDeduct: Int = 0
    var inactiveDaysToClear: Int = 0
    var taxBrackets: List<Map<String, Any>> = ArrayList()
    var isPercentileThresholds: Boolean = false
    var isRoutine: Boolean = true
    var composition: List<String> = ArrayList()
    var exemptPermission: String? = ""
        set(value) {
            field = value ?: ""
        }
    var debtMode: String? = "inherit"
        set(value) {
            field = value ?: "inherit"
        }

    constructor(name: String?) {
        this.name = name
        description = "Custom Tax Policy"
        scheduleType = "monthly"
    }

    constructor()

    fun calculateTax(balance: Double, policyProvider: Function<String, TaxPolicy?>?): Double {
        var totalTax = calculateBaseTax(balance)

        if (composition.isNotEmpty() && policyProvider != null) {
            for (policyName in composition) {
                if (policyName == name) {
                    continue
                }
                val subPolicy = policyProvider.apply(policyName)
                if (subPolicy != null) {
                    totalTax += subPolicy.calculateTax(balance, policyProvider)
                }
            }
        }

        if (maxDeductionPerPlayer > 0 && totalTax > maxDeductionPerPlayer) {
            totalTax = maxDeductionPerPlayer
        }

        if (balance - totalTax < minBalanceProtection) {
            totalTax = max(0.0, balance - minBalanceProtection)
        }

        return totalTax
    }

    fun calculateBaseTax(balance: Double): Double {
        if (balance <= minBalanceProtection) {
            return 0.0
        }

        var rate = 0.0
        var bestThreshold = -1.0

        for (bracket in taxBrackets) {
            val thObj = bracket["threshold"]
            val threshold = if (thObj is Number) {
                thObj.toDouble()
            } else {
                Double.MAX_VALUE
            }

            if (balance >= threshold && threshold > bestThreshold) {
                bestThreshold = threshold
                rate = (bracket["rate"] as Number).toDouble()
            }
        }

        return balance * rate
    }

    override fun toString(): String = "TaxPolicy{name='$name', schedule='$scheduleType', routine=$isRoutine}"
}
