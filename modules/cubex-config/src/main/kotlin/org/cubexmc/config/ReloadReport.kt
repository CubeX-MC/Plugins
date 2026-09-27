package org.cubexmc.config

public class ReloadReport internal constructor(
    succeeded: List<String>,
    skipped: List<String>,
    failures: List<Failure>,
) {
    private val succeededValues = succeeded.toList()
    private val skippedValues = skipped.toList()
    private val failureValues = failures.toList()
    public fun ok(): Boolean = failureValues.isEmpty()
    public fun succeeded(): List<String> = succeededValues
    public fun skipped(): List<String> = skippedValues
    public fun failures(): List<Failure> = failureValues
    public fun failureSummaries(): List<String> = failureValues.map { it.summary() }

    public class Failure internal constructor(stage: String?, private val causeValue: Exception?) {
        private val stageValue = stage.orEmpty()
        public fun stage(): String = stageValue
        public fun cause(): Exception? = causeValue
        public fun summary(): String = "$stageValue: ${causeValue?.message ?: "unknown error"}"
    }
}
