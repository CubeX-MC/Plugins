package org.cubexmc.contract.gui

/**
 * A translatable reason a [CreateDraft] cannot be signed yet: a `ui.*` language key plus the
 * placeholders that key expects. Keeping validation locale-free lets [CreateDraft.validate] stay a
 * pure, unit-testable function while the GUI renders the text in the viewer's language.
 */
data class DraftProblem(val key: String, val placeholders: Map<String, String> = emptyMap())
