package org.cubexmc.contract.service

import org.cubexmc.contract.model.Contract

class ObjectiveProgressUpdate(
    private val contract: Contract,
    private val added: Int,
    private val completed: Boolean,
    private val result: ServiceResult,
) {
    fun contract(): Contract = contract

    fun added(): Int = added

    fun completed(): Boolean = completed

    fun result(): ServiceResult = result
}
