package org.cubexmc.contract.model

import java.util.UUID

enum class TemplateVisibility { PRIVATE, SERVER }

data class ContractTemplate(
    val id: String,
    val ownerUuid: UUID,
    val ownerName: String,
    val name: String,
    val visibility: TemplateVisibility,
    val spec: ContractSpec,
    val createdAt: Long,
    val updatedAt: Long,
)

