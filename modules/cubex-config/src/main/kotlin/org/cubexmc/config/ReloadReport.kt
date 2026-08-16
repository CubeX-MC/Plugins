package org.cubexmc.config

class ReloadReport internal constructor(
    succeeded: List<String>,
    skipped: List<String>,
    failures: List<Failure>,
) {
    private val succeededValues = succeeded.toList()
    private val skippedValues = skipped.toList()
    private val failureValues = failures.toList()
    fun ok(): Boolean = failureValues.isEmpty()
    fun succeeded(): List<String> = succeededValues
    fun skipped(): List<String> = skippedValues
    fun failures(): List<Failure> = failureValues
    fun failureSummaries(): List<String> = failureValues.map { it.summary() }

    class Failure internal constructor(stage: String?, private val causeValue: Exception?) {
        private val stageValue = stage.orEmpty()
        fun stage(): String = stageValue
        fun cause(): Exception? = causeValue
        fun summary(): String = "$stageValue: ${causeValue?.message ?: "unknown error"}"
    }
}
