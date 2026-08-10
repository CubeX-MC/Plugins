package org.cubexmc.contract.service

import org.cubexmc.contract.model.ContractSpec
import org.cubexmc.contract.model.ContractTemplate
import org.cubexmc.contract.model.TemplateVisibility
import org.cubexmc.contract.storage.ContractTemplateStore
import java.util.UUID

class ContractTemplateService(private val store: ContractTemplateStore, private val maxPerOwner: Int) {
    fun visibleTo(playerUuid: UUID, @Suppress("UNUSED_PARAMETER") canManageServer: Boolean): List<ContractTemplate> =
        store.all().filter { it.ownerUuid == playerUuid || it.visibility == TemplateVisibility.SERVER }

    fun save(ownerUuid: UUID, ownerName: String, name: String, spec: ContractSpec): TemplateResult {
        val clean = name.trim()
        if (clean.isBlank() || clean.length > 48) return TemplateResult.fail("template-name-invalid")
        if (store.all().count { it.ownerUuid == ownerUuid } >= maxPerOwner.coerceAtLeast(1)) {
            return TemplateResult.fail("template-limit")
        }
        val now = System.currentTimeMillis()
        val template = ContractTemplate(UUID.randomUUID().toString(), ownerUuid, ownerName, clean, TemplateVisibility.PRIVATE, spec, now, now)
        store.put(template)
        return TemplateResult.ok(template)
    }

    fun delete(actorUuid: UUID, templateId: String, admin: Boolean): TemplateResult {
        val template = store.find(templateId) ?: return TemplateResult.fail("template-not-found")
        if (template.ownerUuid != actorUuid && !admin) return TemplateResult.fail("template-delete-denied")
        store.remove(templateId)
        return TemplateResult.ok(template)
    }

    fun toggleVisibility(actorUuid: UUID, templateId: String, admin: Boolean): TemplateResult {
        val current = store.find(templateId) ?: return TemplateResult.fail("template-not-found")
        if (!admin) return TemplateResult.fail("template-manage-denied")
        val visibility = if (current.visibility == TemplateVisibility.PRIVATE) TemplateVisibility.SERVER else TemplateVisibility.PRIVATE
        val updated = current.copy(visibility = visibility, updatedAt = System.currentTimeMillis())
        store.put(updated)
        return TemplateResult.ok(updated)
    }
}

data class TemplateResult(val success: Boolean, val reason: String, val template: ContractTemplate?) {
    companion object {
        fun ok(template: ContractTemplate) = TemplateResult(true, "", template)
        fun fail(reason: String) = TemplateResult(false, reason, null)
    }
}
