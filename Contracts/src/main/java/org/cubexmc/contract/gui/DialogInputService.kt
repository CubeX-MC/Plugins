package org.cubexmc.contract.gui

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.util.Text
import java.math.BigDecimal

/**
 * High-version text entry backend built on the Paper 1.21.6+ Dialog API: the create wizard becomes a
 * single native form and the sign step a native confirmation. Only instantiated when
 * [DialogSupport.available] is true, so its Dialog-API references never load on older Paper builds.
 *
 * Escaping a dialog simply leaves the in-progress [CreateDraft] untouched; the player reopens it from
 * the hall's create button — so there is no escape callback to wire.
 */
class DialogInputService(private val plugin: ContractPlugin) {

    /** Native confirmation: consequence text plus "sign" / "cancel" buttons. */
    fun confirm(player: Player, title: String, lines: List<String>, onConfirm: () -> Unit, onCancel: () -> Unit) {
        val body = listOf(DialogBody.plainMessage(comp(lines.joinToString("\n") { Text.color("&#CFD8DC$it") })))
        val yes = ActionButton.create(
            comp(Text.color("&#69DB7C签署执行")),
            comp(Text.color("&#CFD8DC点击立即签署并执行")),
            BUTTON_WIDTH,
            DialogAction.customClick(DialogActionCallback { _, _ -> runMain(onConfirm) }, clickOptions()),
        )
        val no = ActionButton.create(
            comp(Text.color("&#E63946取消")),
            null,
            BUTTON_WIDTH,
            DialogAction.customClick(DialogActionCallback { _, _ -> runMain(onCancel) }, clickOptions()),
        )
        val base = DialogBase.builder(comp(Text.color("&#F4D03F$title")))
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(body)
            .build()
        player.showDialog(Dialog.create { factory -> factory.empty().base(base).type(DialogType.confirmation(yes, no)) })
    }

    /**
     * Native create form. Reads the response back into [draft] (parsing numbers leniently) and then
     * calls [onSubmit] — which validates and either re-shows the form or moves to the confirmation.
     */
    fun createForm(player: Player, draft: CreateDraft, onSubmit: () -> Unit) {
        val maxDescription = plugin.config.getInt("limits.max-description-length", 500)
        val inputs = ArrayList<DialogInput>()
        inputs.add(textInput("title", "&#FFE066标题", draft.title(), 64))
        inputs.add(textInput("desc", "&#FFE066描述(可留空)", draft.description(), maxDescription))
        if (draft.needsCounterparty()) {
            inputs.add(textInput("counterparty", "&#FFE066对方玩家", draft.counterparty(), 32))
        }
        inputs.add(textInput("amount", if (draft.type() == ContractType.SERVICE) "&#FFE066奖金" else "&#FFE066我的押注", numberText(draft.amount()), 16))
        inputs.add(textInput("mediator", if (draft.mediatorRequired()) "&#FFE066仲裁者" else "&#FFE066中间人(可留空)", draft.mediator(), 32))
        if (draft.needsPartnerStake()) {
            inputs.add(textInput("stake", "&#FFE066对方押注", numberText(draft.partnerStake()), 16))
        }
        inputs.add(textInput("days", "&#FFE066有效期(天)", draft.days()?.toString(), 6))

        val body = listOf(DialogBody.plainMessage(comp(Text.color("&#CFD8DC填写后点击下方按钮预览并签署。资金由服务器托管。"))))
        val submit = ActionButton.create(
            comp(Text.color("&#69DB7C预览并签署")),
            comp(Text.color("&#CFD8DC进入签署确认")),
            FORM_BUTTON_WIDTH,
            DialogAction.customClick(DialogActionCallback { response, _ -> applyAndSubmit(draft, response, onSubmit) }, clickOptions()),
        )
        val base = DialogBase.builder(comp(Text.color("&#F4D03F创建合同 · ${plugin.lang().type(draft.type())}")))
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(body)
            .inputs(inputs)
            .build()
        player.showDialog(Dialog.create { factory -> factory.empty().base(base).type(DialogType.notice(submit)) })
    }

    private fun applyAndSubmit(draft: CreateDraft, response: DialogResponseView, onSubmit: () -> Unit) {
        val title = response.getText("title")
        val description = response.getText("desc")
        val counterparty = if (draft.needsCounterparty()) response.getText("counterparty") else null
        val amount = response.getText("amount")
        val mediator = response.getText("mediator")
        val stake = if (draft.needsPartnerStake()) response.getText("stake") else null
        val days = response.getText("days")
        runMain {
            draft.title(blankToNull(Text.stripControl(title ?: "")))
            draft.description(blankToNull(Text.stripControl(description ?: "")))
            if (draft.needsCounterparty()) draft.counterparty(blankToNull(counterparty?.trim()))
            draft.mediator(blankToNull(mediator?.trim()))
            draft.amount(parsePositive(amount))
            if (draft.needsPartnerStake()) draft.partnerStake(parsePositive(stake))
            draft.days(days?.trim()?.toIntOrNull())
            onSubmit()
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun textInput(key: String, label: String, initial: String?, maxLength: Int): DialogInput {
        var builder = DialogInput.text(key, comp(Text.color(label))).width(INPUT_WIDTH).maxLength(maxLength)
        if (!initial.isNullOrEmpty()) {
            builder = builder.initial(initial)
        }
        return builder.build()
    }

    private fun comp(legacy: String) = LegacyComponentSerializer.legacySection().deserialize(legacy)

    private fun clickOptions(): ClickCallback.Options = ClickCallback.Options.builder().build()

    private fun runMain(block: () -> Unit) {
        Bukkit.getScheduler().runTask(plugin, Runnable { block() })
    }

    private fun blankToNull(value: String?): String? = if (value.isNullOrBlank()) null else value

    private fun numberText(value: Double?): String? = value?.let {
        if (it == Math.rint(it)) it.toLong().toString() else BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()
    }

    private fun parsePositive(text: String?): Double? {
        val value = text?.trim()?.toDoubleOrNull() ?: return null
        return if (value > 0 && value.isFinite()) value else null
    }

    private companion object {
        const val INPUT_WIDTH = 200
        const val BUTTON_WIDTH = 150
        const val FORM_BUTTON_WIDTH = 200
    }
}
