package org.cubexmc.regions.service

class ServiceResult private constructor(
    val success: Boolean,
    val reason: String = "",
) {
    companion object {
        fun ok(): ServiceResult = ServiceResult(true)

        fun fail(reason: String): ServiceResult = ServiceResult(false, reason)
    }
}
