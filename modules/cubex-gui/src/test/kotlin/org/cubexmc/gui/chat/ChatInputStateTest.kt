package org.cubexmc.gui.chat

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatInputStateTest {

    private val player = UUID.randomUUID()
    private var now = 1_000L
    private val state = ChatInputState<Unit>(clock = { now })

    @Test
    fun `a line with no pending prompt is not ours`() {
        assertInstanceOf(AcceptResult.NotOurs::class.java, state.accept(player, "hello"))
    }

    @Test
    fun `a pending prompt takes the line and keeps it verbatim`() {
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)

        val result = state.accept(player, "  spaced  ")

        val outcome = assertInstanceOf(AcceptResult.Accepted::class.java, result).outcome
        assertEquals("  spaced  ", assertInstanceOf(ChatOutcome.Submitted::class.java, outcome).text)
    }

    @Test
    fun `the same line arriving through the other event chain is swallowed but not reprocessed`() {
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)
        state.accept(player, "answer")

        // 第二条链路把同一行又送了一遍
        assertInstanceOf(AcceptResult.AlreadyTaken::class.java, state.accept(player, "answer"))
    }

    @Test
    fun `a different line after the prompt was answered is not ours`() {
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)
        state.accept(player, "answer")

        assertInstanceOf(AcceptResult.NotOurs::class.java, state.accept(player, "something else"))
    }

    @Test
    fun `settle clears the dedupe record so later chat flows to public chat`() {
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)
        state.accept(player, "answer")
        state.settle(player)

        assertInstanceOf(AcceptResult.NotOurs::class.java, state.accept(player, "answer"))
    }

    @Test
    fun `cancel keyword is recognised case insensitively`() {
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)

        val result = state.accept(player, " CanCel ")

        val outcome = assertInstanceOf(AcceptResult.Accepted::class.java, result).outcome
        assertInstanceOf(ChatOutcome.Cancelled::class.java, outcome)
    }

    @Test
    fun `clear keyword only counts when the prompt allows clearing`() {
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)
        val asText = assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "clear")).outcome
        assertInstanceOf(ChatOutcome.Submitted::class.java, asText)

        state.settle(player)
        state.open(player, allowClear = true, timeoutMillis = 10_000, payload = Unit)
        val asClear = assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "clear")).outcome
        assertInstanceOf(ChatOutcome.Cleared::class.java, asClear)
    }

    @Test
    fun `a line arriving after the deadline is a timeout, not a submission`() {
        state.open(player, allowClear = false, timeoutMillis = 5_000, payload = Unit)
        now += 5_001

        val outcome = assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "too late")).outcome

        assertInstanceOf(ChatOutcome.TimedOut::class.java, outcome)
    }

    @Test
    fun `expire only fires for the prompt that is still current`() {
        val stale = state.open(player, allowClear = false, timeoutMillis = 1_000, payload = Unit)
        val current = state.open(player, allowClear = false, timeoutMillis = 1_000, payload = Unit)

        assertFalse(state.expire(player, stale))
        assertTrue(state.isPending(player, current))
        assertTrue(state.expire(player, current))
    }

    @Test
    fun `forget drops both the prompt and the dedupe record`() {
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)
        state.accept(player, "answer")

        state.forget(player)

        assertInstanceOf(AcceptResult.NotOurs::class.java, state.accept(player, "answer"))
    }

    @Test
    fun `prompts are isolated per player`() {
        val other = UUID.randomUUID()
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)

        assertInstanceOf(AcceptResult.NotOurs::class.java, state.accept(other, "hi"))
        assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "hi"))
    }
}

class ChatInputStateKeywordTest {

    private val player = UUID.randomUUID()

    @Test
    fun `honours plugin-specific cancel keywords`() {
        val state = ChatInputState<Unit>(cancelKeywords = { listOf("cancel", "取消") })
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)

        val outcome = assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "取消")).outcome

        assertInstanceOf(ChatOutcome.Cancelled::class.java, outcome)
    }

    @Test
    fun `re-reads the keyword supplier on every line so a reload takes effect`() {
        var keyword = "cancel"
        val state = ChatInputState<Unit>(cancelKeywords = { listOf(keyword) })

        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)
        assertInstanceOf(
            ChatOutcome.Cancelled::class.java,
            assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "cancel")).outcome,
        )

        keyword = "abbrechen"
        state.settle(player)
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)
        assertInstanceOf(
            ChatOutcome.Cancelled::class.java,
            assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "abbrechen")).outcome,
        )
    }

    @Test
    fun `a blank keyword never matches`() {
        val state = ChatInputState<Unit>(cancelKeywords = { listOf("") })
        state.open(player, allowClear = false, timeoutMillis = 10_000, payload = Unit)

        val outcome = assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "   ")).outcome

        assertInstanceOf(ChatOutcome.Submitted::class.java, outcome)
    }

    @Test
    fun `a non-positive timeout means the prompt never expires`() {
        var now = 0L
        val state = ChatInputState<Unit>(clock = { now })
        state.open(player, allowClear = false, timeoutMillis = 0, payload = Unit)
        now = Long.MAX_VALUE - 1

        val outcome = assertInstanceOf(AcceptResult.Accepted::class.java, state.accept(player, "still here")).outcome

        assertInstanceOf(ChatOutcome.Submitted::class.java, outcome)
    }
}
